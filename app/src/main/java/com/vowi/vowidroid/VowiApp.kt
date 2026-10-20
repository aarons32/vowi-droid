package com.vowi.vowidroid

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.data.BlockedNumbers
import com.vowi.vowidroid.data.CallLogStore
import com.vowi.vowidroid.telecom.CallManager

class VowiApp : Application() {
    /** The language the notification categories were last named in. */
    private var locales: LocaleList? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguage.applyToApp(this)
        locales = resources.configuration.locales
        CoreLog.sink = { entry ->
            val priority = when (entry.level) {
                CoreLog.Level.DEBUG -> android.util.Log.DEBUG
                CoreLog.Level.INFO -> android.util.Log.INFO
                CoreLog.Level.WARN -> android.util.Log.WARN
                CoreLog.Level.ERROR -> android.util.Log.ERROR
            }
            android.util.Log.println(priority, "VowiDroid/" + entry.tag, entry.message)
        }
        CallLogStore.init(this)
        BlockedNumbers.init(this)
        CallManager.init(this)
        VowiCore.incomingCallHandler = { lineId, call -> CallManager.onIncoming(lineId, call.id, call.number) }
        NotificationChannels.install(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyToApp(this)
        // Android shows the categories under the names they were created with, so they are named again.
        val now = resources.configuration.locales
        if (now != locales) {
            locales = now
            NotificationChannels.relabel(this)
        }
    }
}
