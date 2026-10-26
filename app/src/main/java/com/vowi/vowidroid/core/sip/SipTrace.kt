package com.vowi.vowidroid.core.sip

/** One-line renderings of SIP headers for the log; bodies stay out. */
object SipTrace {
    /** The header section of an outgoing message, lines joined with " | ". */
    fun headerLine(packet: ByteArray): String {
        val text = String(packet, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n").let { if (it < 0) text.length else it }
        return text.substring(0, end).split("\r\n").filter { it.isNotBlank() }.joinToString(" | ")
    }

    /** A received message's headers, lines joined with " | ". */
    fun headerLine(message: SipMessage): String =
        message.headers.entries.joinToString(" | ") { (name, values) -> "$name: ${values.joinToString(", ")}" }
}
