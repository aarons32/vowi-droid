package com.vowi.vowidroid.core.sip

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class SipParseException(message: String) : IOException("sip: $message")

/**
 * One SIP request or response (RFC 3261). Header names are kept lower case
 * and the compact forms IMS cores use are canonicalised.
 */
class SipMessage(
    val method: String,
    val uri: String,
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    val isResponse: Boolean get() = statusCode != 0
    val isRequest: Boolean get() = statusCode == 0

    fun values(name: String): List<String> = headers[name.trim().lowercase()] ?: emptyList()
    fun value(name: String): String = values(name).firstOrNull() ?: ""

    /** Comma-separated header values split into items, respecting quotes and angle brackets. */
    fun items(name: String): List<String> = splitHeaderValues(values(name))

    /** The CSeq number and method, or null when malformed. */
    fun cseq(): Pair<Long, String>? {
        val fields = value("CSeq").trim().split(Regex("\\s+"))
        if (fields.size != 2) return null
        val number = fields[0].toLongOrNull() ?: return null
        if (number < 0 || number > 0xffffffffL) return null
        return number to fields[1].uppercase()
    }

    val callId: String get() = value("Call-ID").trim()

    companion object {
        const val MAX_HEADER_BYTES = 64 shl 10
        private val COMPACT = mapOf("v" to "via", "f" to "from", "t" to "to", "i" to "call-id", "l" to "content-length", "c" to "content-type", "m" to "contact", "e" to "content-encoding", "s" to "subject", "k" to "supported")

        /** Parses a complete datagram (UDP). */
        fun parse(packet: ByteArray): SipMessage {
            val (headerEnd, delimiter) = findHeaderEnd(packet)
            if (headerEnd < 0) throw SipParseException("incomplete SIP headers")
            if (headerEnd > MAX_HEADER_BYTES) throw SipParseException("SIP headers exceed limit")
            val (message, contentLength) = parseHeaderBlock(packet.copyOf(headerEnd))
            val bodyStart = headerEnd + delimiter
            if (contentLength > packet.size - bodyStart) throw SipParseException("incomplete SIP body")
            return message.withBody(packet.copyOfRange(bodyStart, bodyStart + contentLength))
        }

        /** Reads one message from a stream (TCP). Returns null at a clean end of stream. */
        fun read(stream: InputStream): SipMessage? {
            val header = ByteArrayOutputStream()
            var previous = -1
            var sawAnything = false
            while (header.size() <= MAX_HEADER_BYTES) {
                val byte = stream.read()
                if (byte < 0) {
                    if (!sawAnything) return null
                    throw EOFException("sip: connection closed while reading headers")
                }
                sawAnything = true
                header.write(byte)
                // Skip leading CRLF keepalives between messages.
                if (header.size() <= 2 && (byte == '\r'.code || byte == '\n'.code)) {
                    if (byte == '\n'.code) {
                        header.reset()
                        sawAnything = false
                    }
                    continue
                }
                val bytes = header.toByteArray()
                val size = bytes.size
                val crlfCrlf = size >= 4 && bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() && bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()
                val lfLf = size >= 2 && bytes[size - 2] == '\n'.code.toByte() && bytes[size - 1] == '\n'.code.toByte() && (size < 3 || bytes[size - 3] != '\r'.code.toByte())
                if (crlfCrlf || lfLf) {
                    val (headerEnd, _) = findHeaderEnd(bytes)
                    val (message, contentLength) = parseHeaderBlock(bytes.copyOf(headerEnd))
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = stream.read(body, read, contentLength - read)
                        if (count < 0) throw EOFException("sip: connection closed while reading the body")
                        read += count
                    }
                    return message.withBody(body)
                }
                previous = byte
            }
            throw SipParseException("SIP headers exceed limit")
        }

        private fun findHeaderEnd(packet: ByteArray): Pair<Int, Int> {
            val crlf = indexOf(packet, "\r\n\r\n".toByteArray())
            if (crlf >= 0) return crlf to 4
            val lf = indexOf(packet, "\n\n".toByteArray())
            if (lf >= 0) return lf to 2
            return -1 to 0
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (index in 0..haystack.size - needle.size) {
                for (offset in needle.indices) if (haystack[index + offset] != needle[offset]) continue@outer
                return index
            }
            return -1
        }

        private fun parseHeaderBlock(block: ByteArray): Pair<SipMessage, Int> {
            val text = String(block, Charsets.ISO_8859_1).replace("\r\n", "\n")
            val rawLines = text.split("\n")
            val startFields = rawLines[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (startFields.size < 2) throw SipParseException("invalid SIP start line")
            val lines = ArrayList<String>()
            for (raw in rawLines.drop(1)) {
                if (raw.isEmpty()) continue
                if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                    lines[lines.size - 1] = lines.last() + " " + raw.trim()
                    continue
                }
                lines.add(raw)
            }
            val headers = LinkedHashMap<String, MutableList<String>>()
            for (line in lines) {
                val colon = line.indexOf(':')
                if (colon <= 0) throw SipParseException("malformed SIP header")
                var name = line.substring(0, colon).trim().lowercase()
                if (name.isEmpty()) throw SipParseException("empty SIP header name")
                name = COMPACT[name] ?: name
                headers.getOrPut(name) { ArrayList() }.add(line.substring(colon + 1).trim())
            }
            var contentLength = 0
            headers["content-length"]?.lastOrNull()?.let { value ->
                contentLength = value.trim().toIntOrNull() ?: -1
                if (contentLength < 0 || contentLength > 1 shl 20) throw SipParseException("invalid SIP Content-Length")
            }
            if (startFields[0].equals("SIP/2.0", ignoreCase = true)) {
                val status = startFields[1].toIntOrNull() ?: -1
                if (status < 100 || status > 699) throw SipParseException("invalid SIP response status code")
                val reason = startFields.drop(2).joinToString(" ")
                return SipMessage("", "", status, reason, headers, ByteArray(0)) to contentLength
            }
            if (startFields.size != 3 || !startFields[2].equals("SIP/2.0", ignoreCase = true) || startFields[0].isEmpty() || startFields[1].isEmpty()) {
                throw SipParseException("invalid SIP request line")
            }
            return SipMessage(startFields[0].uppercase(), startFields[1], 0, "", headers, ByteArray(0)) to contentLength
        }

        fun splitHeaderValues(values: List<String>): List<String> {
            val result = ArrayList<String>()
            for (value in values) {
                var start = 0
                var quoted = false
                var escaped = false
                var angle = 0
                for ((index, character) in value.withIndex()) {
                    when {
                        escaped -> escaped = false
                        quoted && character == '\\' -> escaped = true
                        character == '"' -> quoted = !quoted
                        !quoted && character == '<' -> angle++
                        !quoted && character == '>' && angle > 0 -> angle--
                        !quoted && angle == 0 && character == ',' -> {
                            value.substring(start, index).trim().takeIf { it.isNotEmpty() }?.let(result::add)
                            start = index + 1
                        }
                    }
                }
                value.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            }
            return result
        }

        /** The first URI inside angle brackets, or the value up to its parameters. */
        fun firstUri(value: String): String {
            val open = value.indexOf('<')
            if (open >= 0) {
                val close = value.indexOf('>', open + 1)
                if (close > open) return value.substring(open + 1, close).trim()
            }
            return value.substringBefore(';').trim()
        }

        /** A `name=value` parameter from a header value, unquoted. */
        fun parameter(value: String, name: String): String? {
            val lower = value.lowercase()
            val needle = name.lowercase() + "="
            var index = lower.indexOf(needle)
            while (index >= 0) {
                val boundary = index == 0 || lower[index - 1] == ';' || lower[index - 1] == ' ' || lower[index - 1] == ','
                if (boundary) {
                    var end = index + needle.length
                    if (end < value.length && value[end] == '"') {
                        val close = value.indexOf('"', end + 1)
                        return if (close > end) value.substring(end + 1, close) else null
                    }
                    while (end < value.length && value[end] != ';' && value[end] != ',' && value[end] != '>' && !value[end].isWhitespace()) end++
                    return value.substring(index + needle.length, end)
                }
                index = lower.indexOf(needle, index + 1)
            }
            return null
        }
    }

    private fun withBody(body: ByteArray) = SipMessage(method, uri, statusCode, reason, headers, body)

    override fun toString(): String = if (isResponse) "SIP/2.0 $statusCode $reason" else "$method $uri"
}

/** Builds outgoing messages line by line. */
class SipBuilder private constructor(private val start: String) {
    private val lines = ArrayList<String>()
    private var body: ByteArray = ByteArray(0)
    private var contentType: String? = null

    fun header(name: String, value: String): SipBuilder {
        if (value.isNotEmpty()) lines.add("$name: $value")
        return this
    }

    fun body(contentType: String, body: ByteArray): SipBuilder {
        this.contentType = contentType
        this.body = body
        return this
    }

    fun build(): ByteArray {
        val out = ArrayList<String>()
        out.add(start)
        out.addAll(lines)
        contentType?.let { out.add("Content-Type: $it") }
        out.add("Content-Length: ${body.size}")
        out.add("")
        out.add("")
        val head = out.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
        val packet = ByteArray(head.size + body.size)
        System.arraycopy(head, 0, packet, 0, head.size)
        System.arraycopy(body, 0, packet, head.size, body.size)
        return packet
    }

    companion object {
        fun request(method: String, uri: String) = SipBuilder("$method $uri SIP/2.0")
        fun response(status: Int, reason: String) = SipBuilder("SIP/2.0 $status $reason")
    }
}
