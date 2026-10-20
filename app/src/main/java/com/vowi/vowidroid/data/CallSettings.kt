package com.vowi.vowidroid.data

import android.content.Context

/** Calling settings kept on the phone: call waiting, caller ID per line, and logging to the phone's call history. */
object CallSettings {
    private const val PREFERENCES = "call_settings"

    /** How a line presents our number: the network's default, always shown, or withheld. */
    enum class CallerId { NETWORK, SHOW, HIDE }

    /** Terminal-based call waiting (3GPP TS 24.615); on unless the user turns it off. */
    fun callWaiting(context: Context): Boolean = preferences(context).getBoolean("call_waiting", true)

    fun setCallWaiting(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean("call_waiting", enabled).apply()

    fun callerId(context: Context, lineId: String): CallerId =
        runCatching { CallerId.valueOf(preferences(context).getString("caller_id.$lineId", null) ?: CallerId.NETWORK.name) }.getOrDefault(CallerId.NETWORK)

    fun setCallerId(context: Context, lineId: String, value: CallerId) =
        preferences(context).edit().putString("caller_id.$lineId", value.name).apply()

    /** Sign this line in to the ePDG with an anonymous name and the IMSI encrypted, where its carrier supports it. */
    fun imsiPrivacy(context: Context, lineId: String): Boolean = preferences(context).getBoolean("imsi_privacy.$lineId", true)

    fun setImsiPrivacy(context: Context, lineId: String, enabled: Boolean) = preferences(context).edit().putBoolean("imsi_privacy.$lineId", enabled).apply()

    /** Which network this line's tunnel uses: "auto" for Android's default, or "wifi", "cellular", "ethernet", "vpn". */
    fun networkChoice(context: Context, lineId: String): String = preferences(context).getString("network.$lineId", null)?.takeIf { it.isNotBlank() } ?: "auto"

    fun setNetworkChoice(context: Context, lineId: String, choice: String) = preferences(context).edit().putString("network.$lineId", choice).apply()

    /** Whether VowiDroid's calls also appear in the phone's own call history. */
    fun logToSystem(context: Context): Boolean = preferences(context).getBoolean("log_to_system", true)

    fun setLogToSystem(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean("log_to_system", enabled).apply()

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
