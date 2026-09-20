package dev.muto.app.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import dev.muto.core.dns.DnsMessage
import dev.muto.core.filter.FilterEngine
import dev.muto.core.filter.Verdict
import dev.muto.core.net.IpPackets
import dev.muto.core.stats.FilterStats
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moves DNS between the tunnel and the real resolvers, dropping what the filter says to drop.
 *
 * The tunnel only carries traffic addressed to Muto's fake resolver - see [TunnelAddresses] - so
 * every packet arriving here is a DNS query. For each one we either answer locally (blocked) or
 * relay it to the upstream resolver over a socket the VPN framework has been asked to leave
 * outside the tunnel, then write the reply back as if the fake resolver had produced it.
 *
 * Three threads, each with one job, so neither hot path ever waits on the other:
 *  - **reader** blocks on the TUN file descriptor and makes the filtering decision
 *  - **selector** waits on upstream sockets and matches replies to their queries
 *  - **writer** drains a queue into the TUN file descriptor, which both of the others feed
 */
class DnsPacketPump(
    private val tunnel: ParcelFileDescriptor,
    private val engine: FilterEngine,
    private val stats: FilterStats,
    private val upstreamProvider: () -> List<InetSocketAddress>,
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val onQuery: (QueryRecord) -> Unit,
    private val onFatalError: (Throwable) -> Unit,
) {

    /** One decision, handed to the log. */
    data class QueryRecord(
        val host: String,
        val type: Int,
        val verdict: Verdict,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val running = AtomicBoolean(false)
    private val selector: Selector = Selector.open()

    /** Replies waiting to be written back into the tunnel. */
    private val writeQueue = ArrayBlockingQueue<ByteArray>(WRITE_QUEUE_DEPTH)

    /** Sockets the reader opened that the selector thread still has to register. */
    private val registrations = ConcurrentLinkedQueue<Pending>()

    /** Bumped on the reader thread, dropped on the selector thread. */
    private val inFlight = AtomicInteger()

    private var readerThread: Thread? = null
    private var selectorThread: Thread? = null
    private var writerThread: Thread? = null

    /** A query relayed upstream, with everything needed to address its reply back to the caller. */
    private class Pending(
        val channel: DatagramChannel,
        val request: IpPackets.UdpPacket,
        val deadline: Long,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        writerThread = thread("muto-tun-write", ::writeLoop)
        selectorThread = thread("muto-upstream", ::selectLoop)
        readerThread = thread("muto-tun-read", ::readLoop)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Interrupting the reader is not enough: a blocking read on the TUN descriptor only
        // returns once the descriptor itself is closed, which the service does after this.
        readerThread?.interrupt()
        // Wake the selector rather than close it from here: closing it under a blocked select()
        // raises ClosedSelectorException on that thread. It closes itself on the way out.
        selector.wakeup()
        writerThread?.interrupt()
        drainRegistrations { it.channel.closeQuietly() }
    }

    // ---- reader -----------------------------------------------------------------------------

    private fun readLoop() {
        val input = FileInputStream(tunnel.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET_SIZE)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length <= 0) {
                    // A zero-length read on a blocking TUN means the descriptor went away.
                    if (length == 0) continue else break
                }
                handleOutboundPacket(buffer, length)
            }
        } catch (e: IOException) {
            if (running.get()) fail(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleOutboundPacket(buffer: ByteArray, length: Int) {
        val protocol = IpPackets.protocolOf(buffer, length)
        if (protocol == IpPackets.PROTOCOL_TCP) {
            // Muto has no TCP stack. Clients only fall back to TCP after a truncated reply, which
            // we never send, so dropping these costs nothing in practice.
            return
        }
        val packet = IpPackets.parseUdp(buffer, length) ?: return
        if (packet.destinationPort != DNS_PORT) return

        val question = DnsMessage.parseQuestion(packet.payload)
        if (question == null) {
            // Not a query shape we understand - a multi-question packet, say. Relay it untouched
            // rather than answer it wrongly.
            relayUpstream(packet)
            return
        }

        val verdict = engine.decide(question.name)
        onQuery(QueryRecord(question.name, question.type, verdict))

        if (verdict.blocked) {
            stats.recordBlocked()
            val response = DnsMessage.buildBlockedResponse(packet.payload, question, engine.blockMode())
            enqueueWrite(IpPackets.buildUdpReply(packet, response))
        } else {
            stats.recordAllowed()
            relayUpstream(packet)
        }
    }

    // ---- upstream ---------------------------------------------------------------------------

    private fun relayUpstream(packet: IpPackets.UdpPacket) {
        val upstreams = upstreamProvider()
        if (upstreams.isEmpty()) {
            stats.recordUpstreamFailure()
            return
        }
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            // Under a burst, dropping is the right failure: DNS clients retry, and running the
            // file descriptor table dry would take the whole tunnel down.
            stats.recordUpstreamFailure()
            return
        }

        val channel = try {
            DatagramChannel.open().apply { configureBlocking(false) }
        } catch (e: IOException) {
            stats.recordUpstreamFailure()
            return
        }

        // Without this the query would be routed back into our own tunnel and loop forever.
        if (!protectSocket(channel.socket())) {
            channel.closeQuietly()
            stats.recordUpstreamFailure()
            return
        }

        try {
            channel.connect(upstreams.first())
            channel.write(ByteBuffer.wrap(packet.payload))
        } catch (e: IOException) {
            channel.closeQuietly()
            stats.recordUpstreamFailure()
            return
        }

        inFlight.incrementAndGet()
        registrations.add(Pending(channel, packet, System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS))
        selector.wakeup()
    }

    private fun selectLoop() {
        val buffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE)
        try {
            while (running.get()) {
                registerPending()
                // The timeout doubles as the sweep interval for queries that never got an answer.
                selector.select(SELECT_TIMEOUT_MS)
                if (!running.get()) break

                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (key.isReadable) deliverReply(key, buffer)
                }
                expireStaleQueries()
            }
        } catch (e: IOException) {
            if (running.get()) fail(e)
        } catch (e: ClosedSelectorException) {
            // Only reachable if something closed the selector out from under us; stopping anyway.
        } finally {
            closeAllKeys()
            runCatching { selector.close() }
        }
    }

    private fun registerPending() {
        drainRegistrations { pending ->
            try {
                pending.channel.register(selector, SelectionKey.OP_READ, pending)
            } catch (e: Exception) {
                pending.channel.closeQuietly()
                inFlight.decrementAndGet()
            }
        }
    }

    private fun deliverReply(key: SelectionKey, buffer: ByteBuffer) {
        val pending = key.attachment() as? Pending ?: return
        buffer.clear()
        val received = try {
            (key.channel() as DatagramChannel).read(buffer)
        } catch (e: IOException) {
            stats.recordUpstreamFailure()
            -1
        }
        closeKey(key)
        if (received <= 0) return

        buffer.flip()
        val response = ByteArray(received)
        buffer.get(response)
        enqueueWrite(IpPackets.buildUdpReply(pending.request, response))
    }

    private fun expireStaleQueries() {
        val now = System.currentTimeMillis()
        for (key in selector.keys()) {
            val pending = key.attachment() as? Pending ?: continue
            if (pending.deadline <= now) {
                // Say nothing to the client: a lost DNS query is a situation every resolver
                // already knows how to retry, and a synthetic SERVFAIL would be cached.
                stats.recordUpstreamFailure()
                closeKey(key)
            }
        }
    }

    // ---- writer -----------------------------------------------------------------------------

    private fun enqueueWrite(packet: ByteArray) {
        if (!writeQueue.offer(packet)) {
            // The queue is only this deep if the device has stopped draining, in which case the
            // tunnel is going down anyway.
            Log.w(TAG, "Tunnel write queue full; dropping a reply")
        }
    }

    private fun writeLoop() {
        val output = FileOutputStream(tunnel.fileDescriptor)
        try {
            while (running.get()) {
                val packet = writeQueue.take()
                output.write(packet)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            if (running.get()) fail(e)
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private inline fun drainRegistrations(action: (Pending) -> Unit) {
        while (true) {
            val pending = registrations.poll() ?: return
            action(pending)
        }
    }

    private fun closeKey(key: SelectionKey) {
        key.cancel()
        (key.channel() as? DatagramChannel)?.closeQuietly()
        inFlight.decrementAndGet()
    }

    private fun closeAllKeys() {
        if (!selector.isOpen) return
        for (key in selector.keys()) {
            (key.channel() as? DatagramChannel)?.closeQuietly()
        }
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "Packet pump stopped", e)
        running.set(false)
        onFatalError(e)
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }

    private fun DatagramChannel.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        private const val TAG = "MutoPump"
        private const val DNS_PORT = 53

        /**
         * Comfortably above the 4096-byte EDNS buffer a client can advertise, so an upstream reply
         * never has to be truncated on its way back through the tunnel.
         */
        const val MAX_PACKET_SIZE = 8192

        /** Bounded so a burst cannot exhaust the process's file descriptors. */
        private const val MAX_IN_FLIGHT = 512

        private const val UPSTREAM_TIMEOUT_MS = 5_000L
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val WRITE_QUEUE_DEPTH = 1024
    }
}
