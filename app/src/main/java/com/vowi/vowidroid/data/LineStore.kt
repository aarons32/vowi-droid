package com.vowi.vowidroid.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * A line the user added: one subscriber, wherever their SIM is. The IMSI is
 * the line's main key, so the line, its texts, calls and settings follow the
 * SIM from one slot to another, into a USB reader, or onto a replacement
 * card with the same IMSI. The ICCID names the card the subscriber is on.
 */
data class LineConfig(
    val id: String,
    /** The phone slot the card was last in, or -1. A line saved before cards were tracked finds its card here once. */
    val slot: Int,
    val name: String,
    val enabled: Boolean,
    /** The subscriber: the line's main key. Null for a line saved before cards were tracked, until its card is first seen. */
    val imsi: String? = null,
    /** The card the subscriber was last on. */
    val iccid: String? = null,
    /** Android's subscription ID for the card, or -1; it can reach a SIM the phone switched off. */
    val subscriptionId: Int = -1,
    /** Eject was used: the line stays off while its card is still in. */
    val ejected: Boolean = false,
    /** Where the card was last seen (SimLocation.tag), e.g. "slot:2". */
    val lastSeen: String? = null,
)

/** The user's lines, kept in preferences as JSON. */
class LineStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("line_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<LineConfig>>() {}.type

    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun all(): List<LineConfig> = synchronized(this) {
        val json = preferences.getString(KEY, null) ?: return emptyList()
        val saved = runCatching { gson.fromJson<List<LineConfig>>(json, type) }.getOrNull().orEmpty()
        // Gson fills the fields an older save lacks with null, whatever Kotlin declares.
        saved.filter { it.id != null }.map { it.copy(name = it.name ?: it.id) }
    }

    fun save(lines: List<LineConfig>) = synchronized(this) {
        preferences.edit().putString(KEY, gson.toJson(lines, type)).apply()
    }

    fun add(line: LineConfig) = synchronized(this) { save(all().filter { it.id != line.id } + line) }

    fun remove(id: String) = synchronized(this) { save(all().filter { it.id != id }) }

    fun update(id: String, change: (LineConfig) -> LineConfig) = synchronized(this) { save(all().map { if (it.id == id) change(it) else it }) }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    /** A removed line's ID, kept by its subscriber, so adding the same SIM again carries its texts and calls on. */
    fun rememberRemoved(imsi: String, id: String) = synchronized(this) {
        preferences.edit().putString(REMOVED_PREFIX + imsiKey(imsi), id).apply()
    }

    fun removedId(imsi: String): String? = synchronized(this) { preferences.getString(REMOVED_PREFIX + imsiKey(imsi), null) }

    private fun imsiKey(imsi: String): String = com.vowi.vowidroid.core.sim.SimCards.lineId(imsi)

    companion object {
        private const val KEY = "lines"
        private const val REMOVED_PREFIX = "removed."

        /** The ID lines added from a phone slot had before lines followed their cards. */
        fun idFor(slot: Int) = "phone-sim-${slot + 1}"

        /** Names VowiDroid used to make up from the slot, e.g. "Phone SIM 2 · T-Mobile", lose the slot now that lines follow their cards. */
        fun withoutSlot(name: String): String = Regex("^Phone SIM \\d+ · (.+)$").find(name)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: name
    }
}
