package com.vowi.vowidroid.core.entitlement

/**
 * TS.43 entitlement servers VowiDroid knows by home network. Android gets
 * these from each carrier's configuration, which is not public; the phone
 * tested here carries none. The user can set any server per line.
 */
object EntitlementServers {
    private val byPlmn: Map<String, String> = mapOf(
        // T-Mobile US. The host is the one T-Mobile's own settings for iPhones name, and it answers only US addresses;
        // /ts43 is the only path on it that answers TS.43 requests (everything else is 404).
        "310260" to "https://eas3.msg.t-mobile.com/ts43",
        "310240" to "https://eas3.msg.t-mobile.com/ts43",
        "310200" to "https://eas3.msg.t-mobile.com/ts43",
        "310210" to "https://eas3.msg.t-mobile.com/ts43",
        "310220" to "https://eas3.msg.t-mobile.com/ts43",
        "310230" to "https://eas3.msg.t-mobile.com/ts43",
        "310250" to "https://eas3.msg.t-mobile.com/ts43",
        "310270" to "https://eas3.msg.t-mobile.com/ts43",
        "310310" to "https://eas3.msg.t-mobile.com/ts43",
        "310490" to "https://eas3.msg.t-mobile.com/ts43",
        "310660" to "https://eas3.msg.t-mobile.com/ts43",
        "310800" to "https://eas3.msg.t-mobile.com/ts43",
    )

    /** The server for a SIM's home network, or null when none is known. */
    fun forPlmn(mcc: String, mnc: String): String? {
        val plmn = mcc.trim() + mnc.trim()
        return byPlmn[plmn] ?: byPlmn[mcc.trim() + mnc.trim().padStart(3, '0')]
    }
}
