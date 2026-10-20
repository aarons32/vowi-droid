package com.vowi.vowidroid.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** The texts offered when declining a call with a message; Telecom's defaults until the user edits them. */
object QuickResponses {
    private const val PREFERENCES = "quick_responses"
    private const val KEY = "responses"
    private val gson = Gson()
    private val type = object : TypeToken<List<String>>() {}.type

    val defaults = listOf(
        "Can't talk now. What's up?",
        "I'll call you right back.",
        "I'll call you later.",
        "Can't talk now. Call me later?",
    )

    fun all(context: Context): List<String> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null) ?: return defaults
        return runCatching { gson.fromJson<List<String>>(json, type) }.getOrNull()?.filter { it.isNotBlank() }?.ifEmpty { null } ?: defaults
    }

    fun save(context: Context, responses: List<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(responses.filter { it.isNotBlank() }, type)).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
