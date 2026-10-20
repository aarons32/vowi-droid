package com.vowi.vowidroid.core.carrier

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader

/**
 * The SMS centres VowiDroid knows by home network, from `sms_centers.json`.
 * Android lets only privileged apps read the SMS centre off the phone's own
 * SIM, so without this table a phone-SIM line could not send a text until the
 * user typed the number. The file is hand-maintained with a source per entry;
 * the carrier-bundle importer never touches it, so a re-import keeps it.
 */
object SmsCenters {
    /**
     * One carrier's centre and the home networks it serves. An entry naming
     * [profiles] is for the lines resolved to one of those carrier profiles
     * only: an MVNO with its own centre on its host network's PLMN.
     */
    data class Entry(
        val carrier: String,
        val country: String,
        val homePlmns: List<String>,
        val smsCenter: String,
        val source: String,
        val note: String = "",
        val profiles: List<String> = emptyList(),
    )

    @Volatile
    private var loader: (() -> InputStream)? = null

    @Volatile
    private var entries: List<Entry>? = null

    /** Points the table at its JSON source; it is parsed on first use. */
    fun install(source: () -> InputStream) {
        loader = source
        entries = null
    }

    fun all(): List<Entry> {
        entries?.let { return it }
        synchronized(this) {
            entries?.let { return it }
            val parsed = loader?.invoke()?.use { parse(it) } ?: emptyList()
            entries = parsed
            return parsed
        }
    }

    /** The centre for a SIM's home network, from the entries that serve every carrier on it, or "" when none is known. */
    fun forPlmn(mcc: String, mnc: String): String {
        val wanted = CarrierProfiles.canonicalPlmn(mcc, mnc)
        if (wanted.isEmpty()) return ""
        return all().firstOrNull { entry -> entry.profiles.isEmpty() && entry.serves(wanted) }?.smsCenter.orEmpty()
    }

    /** The centre for a line: an entry naming its carrier profile first, else its home network's. */
    fun forProfile(profileId: String, mcc: String, mnc: String): String {
        val wanted = CarrierProfiles.canonicalPlmn(mcc, mnc)
        if (wanted.isEmpty()) return ""
        val own = all().firstOrNull { entry -> profileId.isNotEmpty() && profileId in entry.profiles && entry.serves(wanted) }
        return own?.smsCenter ?: forPlmn(mcc, mnc)
    }

    private fun Entry.serves(canonicalPlmn: String): Boolean = homePlmns.any { CarrierProfiles.canonicalPlmnValue(it) == canonicalPlmn }

    /** The number a text goes through: the user's own entry, else the SIM's, else the carrier's usual one. */
    fun choose(userEntry: String, fromSim: String, fromCarrier: String): String =
        userEntry.trim().ifEmpty { fromSim.trim() }.ifEmpty { fromCarrier.trim() }

    fun parse(input: InputStream): List<Entry> {
        val root = JsonParser.parseReader(InputStreamReader(input, Charsets.UTF_8))
        if (!root.isJsonObject) return emptyList()
        val list = root.asJsonObject.getAsJsonArray("entries") ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = item.text("sms_center")
            val plmns = item.getAsJsonArray("home_plmns")?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }.orEmpty()
            if (number.isEmpty() || plmns.isEmpty()) return@mapNotNull null
            Entry(
                carrier = item.text("carrier"),
                country = item.text("country"),
                homePlmns = plmns,
                smsCenter = number,
                source = item.text("source"),
                note = item.text("note"),
                profiles = item.getAsJsonArray("profiles")?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim().ifEmpty { null } else null }.orEmpty(),
            )
        }
    }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
}
