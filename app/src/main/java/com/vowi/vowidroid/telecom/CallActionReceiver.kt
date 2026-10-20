package com.vowi.vowidroid.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Decline and hang-up buttons in call notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> CallManager.reject()
            ACTION_HANG_UP -> CallManager.hangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.vowi.vowidroid.DECLINE"
        const val ACTION_HANG_UP = "com.vowi.vowidroid.HANG_UP"

        fun intent(context: Context, action: String): Intent =
            Intent(context, CallActionReceiver::class.java).setAction(action)
    }
}
