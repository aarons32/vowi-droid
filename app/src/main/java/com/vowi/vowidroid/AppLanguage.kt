package com.vowi.vowidroid

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language VowiDroid shows: the system's, or one the user picks.
 * Android 13 and later keep the choice themselves as the app's own language,
 * which the app's page in Android's settings also offers. Older versions keep
 * it here, and VowiDroid applies it to every activity ([wrap]) and to the
 * app's shared resources ([applyToApp]), which its service and notifications
 * use.
 */
object AppLanguage {
    /** Follow the phone's language. */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val CHINESE = "zh-Hans"

    /** What the picker offers, in order. */
    val choices = listOf(SYSTEM, ENGLISH, CHINESE)

    private const val PREFERENCES = "app_language"
    private const val KEY_TAG = "tag"

    /** The current choice, as one of [choices] or the tag Android's settings set. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) SYSTEM else choiceFor(locales.get(0))
        } else {
            stored(context)
        }

    /** The picker's entry for a locale: any English is English, any Chinese the Simplified one VowiDroid has. */
    fun choiceFor(locale: Locale): String = when (locale.language) {
        "en" -> ENGLISH
        "zh" -> CHINESE
        else -> locale.toLanguageTag()
    }

    /** A choice in words: each language in its own, the system default in the current one. */
    fun label(context: Context, tag: String): String = when (tag) {
        SYSTEM -> context.getString(R.string.language_system)
        ENGLISH -> context.getString(R.string.language_english)
        CHINESE -> context.getString(R.string.language_chinese)
        else -> Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    }

    /**
     * Switches the language. Android 13+ then recreates the activities and
     * tells the app and its service, which rename the notification categories
     * and post the status notification again; older versions do that here.
     */
    fun set(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        preferences(context).edit().putString(KEY_TAG, tag).apply()
        val app = context.applicationContext
        applyToApp(app)
        NotificationChannels.relabel(app)
        CoreService.refresh(app)
        context.findActivity()?.recreate()
    }

    /** For an activity's attachBaseContext on Android 12 and older: [base] in the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base).ifEmpty { return base }
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }

    /**
     * Android 12 and older: puts the chosen language, or the system's, into
     * the app's shared resources, again after every configuration change,
     * which brings the system's back.
     */
    @Suppress("DEPRECATION") // the only way to change the resources the service and the application context share
    fun applyToApp(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = stored(app)
        val locales = if (tag.isEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(tag)
        val resources = app.resources
        if (resources.configuration.locales == locales) return
        LocaleList.setDefault(locales)
        resources.updateConfiguration(Configuration(resources.configuration).apply { setLocales(locales) }, resources.displayMetrics)
    }

    private fun stored(context: Context): String = preferences(context).getString(KEY_TAG, null).orEmpty()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
