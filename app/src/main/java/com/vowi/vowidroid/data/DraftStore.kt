package com.vowi.vowidroid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Unsent text for each conversation, kept across screens and restarts as AOSP Messaging keeps drafts. */
object DraftStore {
    private const val PREFERENCES = "drafts"
    private val mutableVersion = MutableStateFlow(0)

    /** Changes whenever a draft is saved or cleared, so lists can show them. */
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    fun get(context: Context, deviceId: String, peer: String): String =
        preferences(context).getString(key(deviceId, peer), null).orEmpty()

    fun set(context: Context, deviceId: String, peer: String, text: String) {
        val key = key(deviceId, peer)
        val preferences = preferences(context)
        if (preferences.getString(key, null).orEmpty() == text) return
        preferences.edit().apply { if (text.isBlank()) remove(key) else putString(key, text) }.apply()
        mutableVersion.value = mutableVersion.value + 1
    }

    /** Adds [text] to whatever the conversation's draft already holds. */
    fun append(context: Context, deviceId: String, peer: String, text: String) {
        val existing = get(context, deviceId, peer)
        set(context, deviceId, peer, if (existing.isBlank()) text else existing.trimEnd() + "\n" + text)
    }

    private fun key(deviceId: String, peer: String) = "$deviceId|$peer"

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
