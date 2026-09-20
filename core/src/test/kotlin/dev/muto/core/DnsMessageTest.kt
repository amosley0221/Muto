package dev.muto.core

import dev.muto.core.dns.BlockMode
import dev.muto.core.dns.DnsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    private fun query(name: String, type: Int = DnsMessage.Type.A, id: Int = 0x1234): ByteArray {
        val labels = name.split('.')
        val nameLength = labels.sumOf { it.length + 1 } + 1
        val packet = ByteArray(DnsMessage.HEADER_SIZE + nameLength + 4)
        packet[0] = ((id ushr 8) and 0xFF).toByte()
        packet[1] = (id and 0xFF).toByte()
        packet[2] = 0x01 // RD
        packet[5] = 1    // QDCOUNT = 1
        var at = DnsMessage.HEADER_SIZE
        for (label in labels) {
            packet[at++] = label.length.toByte()
            for (c in label) packet[at++] = c.code.toByte()
        }
        packet[at++] = 0
        packet[at++] = ((type ushr 8) and 0xFF).toByte()
        packet[at++] = (type and 0xFF).toByte()
        packet[at++] = 0
        packet[at] = 1 // IN
        return packet
    }

    @Test
    fun `parses a standard query`() {
        val question = DnsMessage.parseQuestion(query("ads.example.com"))
        assertNotNull(question)
        assertEquals("ads.example.com", question!!.name)
        assertEquals(DnsMessage.Type.A, question.type)
        assertEquals(DnsMessage.Class.IN, question.qclass)
        assertEquals(0x1234, question.transactionId)
        assertTrue(question.recursionDesired)
        assertEquals(query("ads.example.com").size, question.questionEnd)
    }

    @Test
    fun `lower-cases the queried name`() {
        val question = DnsMessage.parseQuestion(query("Ads.EXAMPLE.Com"))
        assertEquals("ads.example.com", question!!.name)
    }

    @Test
    fun `rejects truncated packets`() {
        assertNull(DnsMessage.parseQuestion(ByteArray(4)))
        val short = query("example.com").copyOf(DnsMessage.HEADER_SIZE + 3)
        assertNull(DnsMessage.parseQuestion(short))
    }

    @Test
    fun `rejects responses and non-standard opcodes`() {
        val response = query("example.com").also { it[2] = 0x81.toByte() }
        assertNull(DnsMessage.parseQuestion(response))

        val update = query("example.com").also { it[2] = (0x05 shl 3).toByte() }
        assertNull(DnsMessage.parseQuestion(update))
    }

    @Test
    fun `rejects a compression pointer inside a question`() {
        val packet = query("example.com")
        packet[DnsMessage.HEADER_SIZE] = 0xC0.toByte()
        assertNull(DnsMessage.parseQuestion(packet))
    }

    @Test
    fun `rejects multi-question packets we cannot answer coherently`() {
        val packet = query("example.com").also { it[5] = 2 }
        assertNull(DnsMessage.parseQuestion(packet))
    }

    @Test
    fun `nxdomain response keeps the question and sets the rcode`() {
        val request = query("ads.example.com")
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.NXDOMAIN)

        assertEquals(0x1234, DnsMessage.transactionId(response))
        assertEquals(DnsMessage.ResponseCode.NAME_ERROR, response[3].toInt() and 0x0F)
        assertTrue("QR bit must be set", response[2].toInt() and 0x80.toByte().toInt() != 0)
        assertEquals(1, readUShort(response, 4))  // QDCOUNT preserved
        assertEquals(0, readUShort(response, 6))  // no answers
        assertEquals(request.size, response.size)
        // The question section is copied through verbatim so the client can match it.
        assertTrue(
            request.copyOfRange(DnsMessage.HEADER_SIZE, request.size)
                .contentEquals(response.copyOfRange(DnsMessage.HEADER_SIZE, response.size)),
        )
    }

    @Test
    fun `null ip response answers A with 0000`() {
        val request = query("ads.example.com", DnsMessage.Type.A)
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.NULL_IP)

        assertEquals(DnsMessage.ResponseCode.NO_ERROR, response[3].toInt() and 0x0F)
        assertEquals(1, readUShort(response, 6))

        val answerAt = question.questionEnd
        assertEquals(0xC00C, readUShort(response, answerAt))            // pointer to the name
        assertEquals(DnsMessage.Type.A, readUShort(response, answerAt + 2))
        assertEquals(DnsMessage.Class.IN, readUShort(response, answerAt + 4))
        assertEquals(4, readUShort(response, answerAt + 10))            // RDLENGTH
        val address = response.copyOfRange(answerAt + 12, answerAt + 16)
        assertTrue(address.all { it.toInt() == 0 })
    }

    @Test
    fun `null ip response answers AAAA with the all-zero address`() {
        val request = query("ads.example.com", DnsMessage.Type.AAAA)
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.NULL_IP)

        val answerAt = question.questionEnd
        assertEquals(DnsMessage.Type.AAAA, readUShort(response, answerAt + 2))
        assertEquals(16, readUShort(response, answerAt + 10))
        assertEquals(question.questionEnd + 12 + 16, response.size)
    }

    @Test
    fun `null ip mode answers other record types with NODATA`() {
        // Type 65 (HTTPS) has no null value, so we must not invent an answer for it.
        val request = query("ads.example.com", type = 65)
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.NULL_IP)

        assertEquals(DnsMessage.ResponseCode.NO_ERROR, response[3].toInt() and 0x0F)
        assertEquals(0, readUShort(response, 6))
        assertEquals(request.size, response.size)
    }

    @Test
    fun `refused mode sets rcode 5`() {
        val request = query("ads.example.com")
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.REFUSED)
        assertEquals(DnsMessage.ResponseCode.REFUSED, response[3].toInt() and 0x0F)
    }

    @Test
    fun `blocked response drops any EDNS additional record`() {
        // A query with an OPT record in the additional section: ARCOUNT must come back as 0,
        // otherwise the client counts a record that is not there.
        val base = query("ads.example.com")
        val withOpt = base.copyOf(base.size + 11).also { it[11] = 1 }
        val question = DnsMessage.parseQuestion(withOpt)!!
        val response = DnsMessage.buildBlockedResponse(withOpt, question, BlockMode.NXDOMAIN)
        assertEquals(0, readUShort(response, 10))
        assertEquals(base.size, response.size)
    }

    @Test
    fun `reads a compressed name from a response`() {
        val request = query("ads.example.com")
        val question = DnsMessage.parseQuestion(request)!!
        val response = DnsMessage.buildBlockedResponse(request, question, BlockMode.NULL_IP)
        assertEquals("ads.example.com", DnsMessage.readName(response, question.questionEnd))
    }

    @Test
    fun `refuses to loop on a self-referential pointer`() {
        val packet = ByteArray(32)
        packet[20] = 0xC0.toByte()
        packet[21] = 20
        assertNull(DnsMessage.readName(packet, 20))
    }

    private fun readUShort(buf: ByteArray, at: Int) =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)
}
