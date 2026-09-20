package dev.muto.core.net

/**
 * Just enough IPv4/IPv6 + UDP to talk to the TUN device.
 *
 * Muto routes only the resolver address into the tunnel, so everything that arrives here is a DNS
 * datagram aimed at our fake resolver. We parse it, and when we have an answer we hand back a
 * packet addressed the other way round.
 */
object IpPackets {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8

    /** A UDP datagram lifted out of its IP packet, with the addressing needed to reply. */
    data class UdpPacket(
        val ipVersion: Int,
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    ) {
        // ByteArray fields make the generated equals/hashCode identity-based, which is wrong for a
        // value type and bites as soon as one of these ends up in a set.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpPacket) return false
            return ipVersion == other.ipVersion &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                sourceAddress.contentEquals(other.sourceAddress) &&
                destinationAddress.contentEquals(other.destinationAddress) &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = ipVersion
            result = 31 * result + sourceAddress.contentHashCode()
            result = 31 * result + destinationAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /** The IP protocol number of [buffer], or -1 if it is not a packet we can read. */
    fun protocolOf(buffer: ByteArray, length: Int): Int {
        if (length < 1) return -1
        return when ((buffer[0].toInt() and 0xF0) ushr 4) {
            4 -> if (length < IPV4_MIN_HEADER) -1 else buffer[9].toInt() and 0xFF
            6 -> if (length < IPV6_HEADER) -1 else buffer[6].toInt() and 0xFF
            else -> -1
        }
    }

    /**
     * Parses a UDP datagram out of an IP packet. Returns null for anything else: fragments,
     * IPv6 extension headers, truncated reads. Those get dropped rather than guessed at.
     */
    fun parseUdp(buffer: ByteArray, length: Int): UdpPacket? {
        if (length < 1) return null
        return when ((buffer[0].toInt() and 0xF0) ushr 4) {
            4 -> parseUdpV4(buffer, length)
            6 -> parseUdpV6(buffer, length)
            else -> null
        }
    }

    private fun parseUdpV4(buffer: ByteArray, length: Int): UdpPacket? {
        if (length < IPV4_MIN_HEADER) return null
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_MIN_HEADER || headerLength > length) return null
        if (buffer[9].toInt() and 0xFF != PROTOCOL_UDP) return null

        // Reassembling fragments is not worth it for DNS, which never fragments in practice.
        val fragmentField = readUShort(buffer, 6)
        val moreFragments = fragmentField and 0x2000 != 0
        val fragmentOffset = fragmentField and 0x1FFF
        if (moreFragments || fragmentOffset != 0) return null

        val totalLength = readUShort(buffer, 2).coerceAtMost(length)
        if (totalLength < headerLength + UDP_HEADER) return null

        val udpAt = headerLength
        val udpLength = readUShort(buffer, udpAt + 4)
        val payloadLength = (udpLength - UDP_HEADER).coerceAtMost(totalLength - udpAt - UDP_HEADER)
        if (payloadLength < 0) return null

        return UdpPacket(
            ipVersion = 4,
            sourceAddress = buffer.copyOfRange(12, 16),
            destinationAddress = buffer.copyOfRange(16, 20),
            sourcePort = readUShort(buffer, udpAt),
            destinationPort = readUShort(buffer, udpAt + 2),
            payload = buffer.copyOfRange(udpAt + UDP_HEADER, udpAt + UDP_HEADER + payloadLength),
        )
    }

    private fun parseUdpV6(buffer: ByteArray, length: Int): UdpPacket? {
        if (length < IPV6_HEADER + UDP_HEADER) return null
        // Extension headers would need a chain walk; a DNS query from the system resolver has none.
        if (buffer[6].toInt() and 0xFF != PROTOCOL_UDP) return null

        val payloadLength = readUShort(buffer, 4)
        val available = (length - IPV6_HEADER).coerceAtMost(payloadLength)
        if (available < UDP_HEADER) return null

        val udpAt = IPV6_HEADER
        val udpLength = readUShort(buffer, udpAt + 4)
        val udpPayloadLength = (udpLength - UDP_HEADER).coerceAtMost(available - UDP_HEADER)
        if (udpPayloadLength < 0) return null

        return UdpPacket(
            ipVersion = 6,
            sourceAddress = buffer.copyOfRange(8, 24),
            destinationAddress = buffer.copyOfRange(24, 40),
            sourcePort = readUShort(buffer, udpAt),
            destinationPort = readUShort(buffer, udpAt + 2),
            payload = buffer.copyOfRange(udpAt + UDP_HEADER, udpAt + UDP_HEADER + udpPayloadLength),
        )
    }

    /**
     * Builds the packet that carries [payload] back to whoever sent [request]: same addressing,
     * source and destination swapped.
     */
    fun buildUdpReply(request: UdpPacket, payload: ByteArray): ByteArray = when (request.ipVersion) {
        4 -> buildUdpV4(
            sourceAddress = request.destinationAddress,
            destinationAddress = request.sourceAddress,
            sourcePort = request.destinationPort,
            destinationPort = request.sourcePort,
            payload = payload,
        )
        else -> buildUdpV6(
            sourceAddress = request.destinationAddress,
            destinationAddress = request.sourceAddress,
            sourcePort = request.destinationPort,
            destinationPort = request.sourcePort,
            payload = payload,
        )
    }

    fun buildUdpV4(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER + payload.size
        val totalLength = IPV4_MIN_HEADER + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45                       // IPv4, 5 word header
        packet[1] = 0                          // DSCP/ECN
        writeUShort(packet, 2, totalLength)
        writeUShort(packet, 4, 0)              // identification: no fragmentation, so 0 is fine
        writeUShort(packet, 6, 0x4000)         // don't fragment
        packet[8] = 64                         // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        sourceAddress.copyInto(packet, 12)
        destinationAddress.copyInto(packet, 16)
        writeUShort(packet, 10, onesComplementChecksum(packet, 0, IPV4_MIN_HEADER))

        val udpAt = IPV4_MIN_HEADER
        writeUShort(packet, udpAt, sourcePort)
        writeUShort(packet, udpAt + 2, destinationPort)
        writeUShort(packet, udpAt + 4, udpLength)
        payload.copyInto(packet, udpAt + UDP_HEADER)
        writeUShort(
            packet,
            udpAt + 6,
            udpChecksum(packet, udpAt, udpLength, sourceAddress, destinationAddress),
        )
        return packet
    }

    fun buildUdpV6(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER + payload.size
        val packet = ByteArray(IPV6_HEADER + udpLength)

        packet[0] = 0x60                       // IPv6, traffic class 0
        writeUShort(packet, 4, udpLength)      // payload length
        packet[6] = PROTOCOL_UDP.toByte()      // next header
        packet[7] = 64                         // hop limit
        sourceAddress.copyInto(packet, 8)
        destinationAddress.copyInto(packet, 24)

        val udpAt = IPV6_HEADER
        writeUShort(packet, udpAt, sourcePort)
        writeUShort(packet, udpAt + 2, destinationPort)
        writeUShort(packet, udpAt + 4, udpLength)
        payload.copyInto(packet, udpAt + UDP_HEADER)
        writeUShort(
            packet,
            udpAt + 6,
            udpChecksum(packet, udpAt, udpLength, sourceAddress, destinationAddress),
        )
        return packet
    }

    /**
     * UDP checksum over the pseudo-header plus the datagram (RFC 768, RFC 8200 §8.1). The checksum
     * field itself must already be zero when this runs.
     */
    private fun udpChecksum(
        packet: ByteArray,
        udpAt: Int,
        udpLength: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): Int {
        var sum = 0L
        sum += sumBytes(sourceAddress, 0, sourceAddress.size)
        sum += sumBytes(destinationAddress, 0, destinationAddress.size)
        sum += PROTOCOL_UDP.toLong()
        sum += udpLength.toLong()
        sum += sumBytes(packet, udpAt, udpLength)

        val checksum = fold(sum)
        // An all-zero checksum means "not computed" for IPv4 and is illegal for IPv6, so the
        // transmitted value flips to all ones. Both encode the same arithmetic result.
        return if (checksum == 0) 0xFFFF else checksum
    }

    /** Ones-complement checksum of a byte range, used for the IPv4 header. */
    fun onesComplementChecksum(buf: ByteArray, offset: Int, length: Int): Int =
        fold(sumBytes(buf, offset, length))

    private fun sumBytes(buf: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var at = offset
        val end = offset + length
        while (at + 1 < end) {
            sum += ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
            at += 2
        }
        if (at < end) sum += (buf[at].toInt() and 0xFF) shl 8
        return sum
    }

    private fun fold(sum: Long): Int {
        var value = sum
        while (value ushr 16 != 0L) {
            value = (value and 0xFFFF) + (value ushr 16)
        }
        return (value.inv() and 0xFFFF).toInt()
    }

    private fun readUShort(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun writeUShort(buf: ByteArray, at: Int, value: Int) {
        buf[at] = ((value ushr 8) and 0xFF).toByte()
        buf[at + 1] = (value and 0xFF).toByte()
    }
}
