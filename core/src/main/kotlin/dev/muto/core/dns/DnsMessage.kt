package dev.muto.core.dns

/**
 * Minimal DNS wire-format reader/writer (RFC 1035).
 *
 * Muto only needs two things from a DNS packet: the name being asked about, so it can be matched
 * against the block lists, and the ability to synthesise an answer when the name is blocked.
 * Everything else is forwarded upstream untouched, so this deliberately stops well short of a
 * general purpose DNS library.
 */
object DnsMessage {

    const val HEADER_SIZE = 12

    /** Largest number of labels we will follow before deciding a name is malformed. */
    private const val MAX_LABELS = 128

    /** A compressed name may not point forward, so this bounds pointer chasing too. */
    private const val MAX_POINTER_HOPS = 32

    object Type {
        const val A = 1
        const val AAAA = 28
    }

    object Class {
        const val IN = 1
    }

    object ResponseCode {
        const val NO_ERROR = 0
        const val FORMAT_ERROR = 1
        const val SERVER_FAILURE = 2
        const val NAME_ERROR = 3 // NXDOMAIN
        const val REFUSED = 5
    }

    /**
     * The parts of a query Muto acts on. [questionEnd] is the offset just past the question
     * section, which is where a synthesised answer gets appended.
     */
    data class Question(
        val transactionId: Int,
        val recursionDesired: Boolean,
        val name: String,
        val type: Int,
        val qclass: Int,
        val questionEnd: Int,
    )

    /**
     * Reads the first question out of [packet]. Returns null for anything that is not a
     * well-formed single-question standard query, which the caller should simply forward upstream
     * rather than try to interpret.
     */
    fun parseQuestion(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Question? {
        if (length < HEADER_SIZE) return null
        val end = offset + length

        val flags = readUShort(packet, offset + 2)
        val isResponse = (flags and 0x8000) != 0
        val opcode = (flags ushr 11) and 0x0F
        if (isResponse || opcode != 0) return null

        val questionCount = readUShort(packet, offset + 4)
        if (questionCount != 1) return null

        val name = StringBuilder()
        var cursor = offset + HEADER_SIZE
        var labels = 0
        while (true) {
            if (cursor >= end) return null
            val lengthByte = packet[cursor].toInt() and 0xFF
            // A query we generated a response for should never be compressed, and accepting
            // pointers in a question would let a malformed packet send us backwards.
            if (lengthByte and 0xC0 != 0) return null
            cursor++
            if (lengthByte == 0) break
            if (cursor + lengthByte > end) return null
            if (++labels > MAX_LABELS) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until lengthByte) {
                name.append((packet[cursor + i].toInt() and 0xFF).toChar())
            }
            cursor += lengthByte
        }

        if (cursor + 4 > end) return null
        val type = readUShort(packet, cursor)
        val qclass = readUShort(packet, cursor + 2)
        cursor += 4

        return Question(
            transactionId = readUShort(packet, offset),
            recursionDesired = (flags and 0x0100) != 0,
            name = name.toString().lowercase(),
            type = type,
            qclass = qclass,
            questionEnd = cursor,
        )
    }

    /**
     * Reads a name starting at [offset], following compression pointers. Used for the query log,
     * where we want to show what an upstream response actually resolved.
     */
    fun readName(packet: ByteArray, offset: Int): String? {
        val name = StringBuilder()
        var cursor = offset
        var hops = 0
        var labels = 0
        while (true) {
            if (cursor < 0 || cursor >= packet.size) return null
            val lengthByte = packet[cursor].toInt() and 0xFF
            if (lengthByte and 0xC0 == 0xC0) {
                if (cursor + 1 >= packet.size) return null
                if (++hops > MAX_POINTER_HOPS) return null
                cursor = ((lengthByte and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
                continue
            }
            if (lengthByte and 0xC0 != 0) return null
            cursor++
            if (lengthByte == 0) break
            if (cursor + lengthByte > packet.size) return null
            if (++labels > MAX_LABELS) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until lengthByte) {
                name.append((packet[cursor + i].toInt() and 0xFF).toChar())
            }
            cursor += lengthByte
        }
        return name.toString().lowercase()
    }

    /**
     * Builds the response Muto sends back for a blocked name.
     *
     * [mode] picks between the three usual strategies. NXDOMAIN is the most widely understood,
     * NULL_IP answers with an unroutable address (some apps retry less aggressively that way), and
     * REFUSED tells the client we declined rather than that the name does not exist.
     */
    fun buildBlockedResponse(query: ByteArray, question: Question, mode: BlockMode): ByteArray {
        val answer = when (mode) {
            BlockMode.NULL_IP -> when (question.type) {
                Type.A -> NULL_IPV4
                Type.AAAA -> NULL_IPV6
                // For HTTPS/SVCB/TXT and friends there is no "null" value, so we answer NOERROR
                // with an empty answer section, which clients read as "exists, nothing to use".
                else -> null
            }
            BlockMode.NXDOMAIN, BlockMode.REFUSED -> null
        }
        val rcode = when (mode) {
            BlockMode.NXDOMAIN -> ResponseCode.NAME_ERROR
            BlockMode.REFUSED -> ResponseCode.REFUSED
            BlockMode.NULL_IP -> ResponseCode.NO_ERROR
        }

        val questionLength = question.questionEnd
        val answerLength = if (answer == null) 0 else ANSWER_PREFIX_SIZE + answer.size
        val response = ByteArray(questionLength + answerLength)
        query.copyInto(response, 0, 0, questionLength)

        // QR=1, RD copied from the query, RA=1 so clients do not think recursion was unavailable.
        var flags = 0x8000 or 0x0080 or rcode
        if (question.recursionDesired) flags = flags or 0x0100
        writeUShort(response, 2, flags)
        writeUShort(response, 4, 1)                        // QDCOUNT
        writeUShort(response, 6, if (answer == null) 0 else 1) // ANCOUNT
        writeUShort(response, 8, 0)                        // NSCOUNT
        writeUShort(response, 10, 0)                       // ARCOUNT: any EDNS OPT is dropped

        if (answer != null) {
            var at = questionLength
            // Name compression pointer back to the question's name at offset 12.
            writeUShort(response, at, 0xC000 or HEADER_SIZE); at += 2
            writeUShort(response, at, question.type); at += 2
            writeUShort(response, at, Class.IN); at += 2
            writeUInt(response, at, BLOCKED_TTL_SECONDS); at += 4
            writeUShort(response, at, answer.size); at += 2
            answer.copyInto(response, at)
        }
        return response
    }

    /** Builds a bare error response, used when we cannot reach any upstream resolver. */
    fun buildErrorResponse(query: ByteArray, question: Question, rcode: Int): ByteArray {
        val response = ByteArray(question.questionEnd)
        query.copyInto(response, 0, 0, question.questionEnd)
        var flags = 0x8000 or 0x0080 or rcode
        if (question.recursionDesired) flags = flags or 0x0100
        writeUShort(response, 2, flags)
        writeUShort(response, 4, 1)
        writeUShort(response, 6, 0)
        writeUShort(response, 8, 0)
        writeUShort(response, 10, 0)
        return response
    }

    fun transactionId(packet: ByteArray, offset: Int = 0): Int = readUShort(packet, offset)

    private const val ANSWER_PREFIX_SIZE = 12 // name pointer + type + class + ttl + rdlength

    /**
     * Short enough that unblocking a domain takes effect quickly, long enough that a page full of
     * blocked requests does not re-query for every asset.
     */
    private const val BLOCKED_TTL_SECONDS = 120L

    private val NULL_IPV4 = byteArrayOf(0, 0, 0, 0)
    private val NULL_IPV6 = ByteArray(16)

    private fun readUShort(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun writeUShort(buf: ByteArray, at: Int, value: Int) {
        buf[at] = ((value ushr 8) and 0xFF).toByte()
        buf[at + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt(buf: ByteArray, at: Int, value: Long) {
        buf[at] = ((value ushr 24) and 0xFF).toByte()
        buf[at + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[at + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[at + 3] = (value and 0xFF).toByte()
    }
}

/** How Muto answers a query for a blocked name. */
enum class BlockMode {
    /** Reply "this name does not exist". The default; understood by every client. */
    NXDOMAIN,

    /** Reply with 0.0.0.0 / ::, which some apps give up on faster than an NXDOMAIN. */
    NULL_IP,

    /** Reply "I refuse to answer". Makes it obvious a filter is in play when debugging. */
    REFUSED,
}
