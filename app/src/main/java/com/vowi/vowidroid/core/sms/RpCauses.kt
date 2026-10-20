package com.vowi.vowidroid.core.sms

/** The RP-Cause values a service centre answers a text with (3GPP TS 24.011 table 8.4), in words. */
object RpCauses {
    private val names = mapOf(
        1 to "unassigned number",
        8 to "operator determined barring",
        10 to "call barred",
        21 to "short message transfer rejected",
        22 to "memory capacity exceeded",
        27 to "destination out of order",
        28 to "unidentified subscriber",
        29 to "facility rejected",
        30 to "unknown subscriber",
        38 to "network out of order",
        41 to "temporary failure",
        42 to "congestion",
        47 to "resources unavailable",
        50 to "requested facility not subscribed",
        69 to "requested facility not implemented",
        81 to "invalid short message transfer reference value",
        95 to "semantically incorrect message",
        96 to "invalid mandatory information",
        97 to "message type non-existent or not implemented",
        98 to "message not compatible with short message protocol state",
        99 to "information element non-existent or not implemented",
        111 to "protocol error, unspecified",
        127 to "interworking, unspecified",
    )

    /** The cause in words, or "cause N" for one the table does not name. */
    fun name(cause: Int): String = names[cause] ?: "cause $cause"
}
