package com.vowi.vowidroid

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import com.vowi.vowidroid.core.entitlement.Ts43
import com.vowi.vowidroid.core.entitlement.VowifiEntitlement
import java.util.UUID

/** What each line's carrier last said about Wi-Fi calling and the emergency address, plus the sign-in token it gave. */
object EntitlementStore {
    private const val PREFERENCES = "entitlement"
    private const val KEY_TERMINAL_ID = "terminal_id"

    /** The last answer, as the screens show it. [checkedAt] is 0 before the first check. */
    data class Summary(
        val checkedAt: Long,
        val entitlementStatus: Int,
        val addrStatus: Int,
        val tcStatus: Int,
        val provStatus: Int,
        val hasPage: Boolean,
        /** The carrier's page for the address, without the data posted to it. */
        val pageUrl: String,
        val viaCellular: Boolean,
        val error: String,
    ) {
        val addressOnFile: Boolean get() = addrStatus == Ts43.STATUS_AVAILABLE
    }

    private fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** This installation's ID at entitlement servers: TS.43 names the IMEI, but VowiDroid keeps the phone's IMEI out of entitlement traffic, so a UUID stands in. */
    fun terminalId(context: Context): String = synchronized(this) {
        val preferences = preferences(context)
        preferences.getString(KEY_TERMINAL_ID, null)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_TERMINAL_ID, it).apply()
        }
    }

    /** The server the user set for a line, or null for the carrier's known one. */
    fun server(context: Context, lineId: String): String? =
        preferences(context).getString("server.$lineId", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setServer(context: Context, lineId: String, server: String) {
        // A new server starts over: its tokens are its own.
        preferences(context).edit().putString("server.$lineId", server.trim()).remove("token.$lineId").apply()
    }

    /** A token still inside its validity, for a query without a new sign-in. */
    fun token(context: Context, lineId: String): String? {
        val preferences = preferences(context)
        val token = preferences.getString("token.$lineId", null)?.takeIf { it.isNotBlank() } ?: return null
        val issued = preferences.getLong("token_at.$lineId", 0)
        val validity = preferences.getLong("token_validity.$lineId", 0)
        if (issued <= 0) return null
        if (validity > 0 && System.currentTimeMillis() - issued >= validity * 1000) return null
        return token
    }

    fun saveAnswer(context: Context, lineId: String, answer: VowifiEntitlement, viaCellular: Boolean) {
        val editor = preferences(context).edit()
            .putLong("checked_at.$lineId", System.currentTimeMillis())
            .putInt("entitlement.$lineId", answer.entitlementStatus)
            .putInt("address.$lineId", answer.addrStatus)
            .putInt("terms.$lineId", answer.tcStatus)
            .putInt("provisioning.$lineId", answer.provStatus)
            .putBoolean("page.$lineId", answer.serviceFlowUrl.isNotEmpty())
            .putString("page_url.$lineId", answer.serviceFlowUrl)
            .putBoolean("cellular.$lineId", viaCellular)
            .remove("error.$lineId")
        if (answer.token.isNotEmpty()) {
            editor.putString("token.$lineId", answer.token)
                .putLong("token_at.$lineId", System.currentTimeMillis())
                .putLong("token_validity.$lineId", answer.tokenValiditySeconds)
        }
        editor.apply()
    }

    fun saveError(context: Context, lineId: String, message: String) {
        preferences(context).edit().putString("error.$lineId", message).putLong("checked_at.$lineId", System.currentTimeMillis()).apply()
    }

    fun forgetToken(context: Context, lineId: String) {
        preferences(context).edit().remove("token.$lineId").apply()
    }

    fun clear(context: Context, lineId: String) {
        val preferences = preferences(context)
        val editor = preferences.edit()
        for (key in preferences.all.keys) if (key.endsWith(".$lineId")) editor.remove(key)
        editor.apply()
    }

    fun summary(context: Context, lineId: String): Summary {
        val preferences = preferences(context)
        return Summary(
            checkedAt = preferences.getLong("checked_at.$lineId", 0),
            entitlementStatus = preferences.getInt("entitlement.$lineId", Ts43.ENTITLEMENT_DISABLED),
            addrStatus = preferences.getInt("address.$lineId", Ts43.STATUS_NOT_AVAILABLE),
            tcStatus = preferences.getInt("terms.$lineId", Ts43.STATUS_NOT_REQUIRED),
            provStatus = preferences.getInt("provisioning.$lineId", Ts43.PROVISIONING_NOT_REQUIRED),
            hasPage = preferences.getBoolean("page.$lineId", false),
            pageUrl = preferences.getString("page_url.$lineId", null).orEmpty(),
            viaCellular = preferences.getBoolean("cellular.$lineId", false),
            error = preferences.getString("error.$lineId", null).orEmpty(),
        )
    }

    /** One line for the line's settings: what the carrier has on file. */
    fun statusText(context: Context, lineId: String): String {
        val summary = summary(context, lineId)
        return when {
            summary.checkedAt == 0L -> context.getString(R.string.emergency_status_unknown)
            summary.error.isNotEmpty() -> context.getString(R.string.emergency_status_error, summary.error)
            else -> statusText(context, summary)
        }
    }

    fun statusText(context: Context, summary: Summary): String = when {
        summary.entitlementStatus == Ts43.ENTITLEMENT_INCOMPATIBLE -> context.getString(R.string.emergency_status_incompatible)
        summary.addrStatus == Ts43.STATUS_AVAILABLE -> context.getString(R.string.emergency_status_on_file)
        summary.addrStatus == Ts43.STATUS_NOT_REQUIRED -> context.getString(R.string.emergency_status_not_required)
        summary.addrStatus == Ts43.STATUS_IN_PROGRESS || summary.entitlementStatus == Ts43.ENTITLEMENT_PROVISIONING ->
            context.getString(R.string.emergency_status_in_progress)
        else -> context.getString(R.string.emergency_status_needed)
    }

    fun checkedText(context: Context, summary: Summary): String =
        if (summary.checkedAt == 0L) "" else context.getString(R.string.emergency_checked_at, DateUtils.getRelativeTimeSpanString(summary.checkedAt))
}
