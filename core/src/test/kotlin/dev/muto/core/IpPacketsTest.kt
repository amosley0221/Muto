package dev.muto.core

import dev.muto.core.net.IpPackets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketsTest {

    private val clientV4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val resolverV4 = byteArrayOf(10, 111, 222.toByte(), 2)
    private val clientV6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
    private val resolverV6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }

    @Test
    fun `round-trips an IPv4 UDP datagram`() {
        val payload = "dns-query-bytes".toByteArray()
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 40000, 53, payload)

        val parsed = IpPackets.parseUdp(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.ipVersion)
        assertArrayEquals(clientV4, parsed.sourceAddress)
        assertArrayEquals(resolverV4, parsed.destinationAddress)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `round-trips an IPv6 UDP datagram`() {
        val payload = "dns-query-bytes".toByteArray()
        val packet = IpPackets.buildUdpV6(clientV6, resolverV6, 40000, 53, payload)

        val parsed = IpPackets.parseUdp(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.ipVersion)
        assertArrayEquals(clientV6, parsed.sourceAddress)
        assertArrayEquals(resolverV6, parsed.destinationAddress)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `IPv4 header checksum verifies to zero`() {
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 1234, 53, ByteArray(20))
        // Re-checksumming a header that already carries a correct checksum yields 0.
        assertEquals(0, IpPackets.onesComplementChecksum(packet, 0, 20))
    }

    @Test
    fun `UDP checksum verifies over the pseudo-header`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 5353, 53, payload)
        assertEquals(0, verifyUdpChecksumV4(packet))
    }

    @Test
    fun `UDP checksum handles an odd-length payload`() {
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 5353, 53, byteArrayOf(0x41))
        assertEquals(0, verifyUdpChecksumV4(packet))
    }

    @Test
    fun `a reply swaps addresses and ports`() {
        val requestBytes = IpPackets.buildUdpV4(clientV4, resolverV4, 40000, 53, byteArrayOf(9))
        val request = IpPackets.parseUdp(requestBytes, requestBytes.size)!!
        val replyBytes = IpPackets.buildUdpReply(request, byteArrayOf(7, 7))
        val reply = IpPackets.parseUdp(replyBytes, replyBytes.size)!!

        assertArrayEquals(resolverV4, reply.sourceAddress)
        assertArrayEquals(clientV4, reply.destinationAddress)
        assertEquals(53, reply.sourcePort)
        assertEquals(40000, reply.destinationPort)
        assertArrayEquals(byteArrayOf(7, 7), reply.payload)
    }

    @Test
    fun `an IPv6 reply keeps the IPv6 shape`() {
        val requestBytes = IpPackets.buildUdpV6(clientV6, resolverV6, 40000, 53, byteArrayOf(9))
        val request = IpPackets.parseUdp(requestBytes, requestBytes.size)!!
        val replyBytes = IpPackets.buildUdpReply(request, byteArrayOf(7, 7))
        val reply = IpPackets.parseUdp(replyBytes, replyBytes.size)!!
        assertEquals(6, reply.ipVersion)
        assertArrayEquals(resolverV6, reply.sourceAddress)
        assertArrayEquals(clientV6, reply.destinationAddress)
    }

    @Test
    fun `reports the protocol without parsing`() {
        val udp = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, ByteArray(0))
        assertEquals(IpPackets.PROTOCOL_UDP, IpPackets.protocolOf(udp, udp.size))

        val tcp = udp.copyOf().also { it[9] = IpPackets.PROTOCOL_TCP.toByte() }
        assertEquals(IpPackets.PROTOCOL_TCP, IpPackets.protocolOf(tcp, tcp.size))
    }

    @Test
    fun `drops non-UDP packets`() {
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, ByteArray(4))
        packet[9] = IpPackets.PROTOCOL_TCP.toByte()
        assertNull(IpPackets.parseUdp(packet, packet.size))
    }

    @Test
    fun `drops IPv4 fragments rather than guessing at them`() {
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, ByteArray(8))
        packet[6] = 0x20 // more-fragments
        assertNull(IpPackets.parseUdp(packet, packet.size))
    }

    @Test
    fun `drops IPv6 packets carrying extension headers`() {
        val packet = IpPackets.buildUdpV6(clientV6, resolverV6, 1, 53, ByteArray(8))
        packet[6] = 43 // routing header
        assertNull(IpPackets.parseUdp(packet, packet.size))
    }

    @Test
    fun `drops packets shorter than their headers claim`() {
        val packet = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, ByteArray(40))
        assertNull(IpPackets.parseUdp(packet, 10))
        assertNull(IpPackets.parseUdp(ByteArray(0), 0))
    }

    @Test
    fun `truncates to the length actually read from the device`() {
        // The TUN read can return fewer bytes than the buffer holds; stale bytes past the read
        // must not leak into the payload.
        val payload = byteArrayOf(1, 2, 3, 4)
        val built = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, payload)
        val buffer = built.copyOf(built.size + 16).also { it.fill(0x7F, built.size) }
        val parsed = IpPackets.parseUdp(buffer, built.size)!!
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `a parsed packet compares by value`() {
        val bytes = IpPackets.buildUdpV4(clientV4, resolverV4, 1, 53, byteArrayOf(5))
        val a = IpPackets.parseUdp(bytes, bytes.size)
        val b = IpPackets.parseUdp(bytes.copyOf(), bytes.size)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /** Recomputes the UDP checksum over the pseudo-header; a valid packet sums to zero. */
    private fun verifyUdpChecksumV4(packet: ByteArray): Int {
        val udpAt = 20
        val udpLength = ((packet[udpAt + 4].toInt() and 0xFF) shl 8) or (packet[udpAt + 5].toInt() and 0xFF)
        var sum = 0L
        for (i in 12 until 20 step 2) sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        sum += IpPackets.PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()
        var at = udpAt
        val end = udpAt + udpLength
        while (at + 1 < end) {
            sum += ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)
            at += 2
        }
        if (at < end) sum += (packet[at].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
