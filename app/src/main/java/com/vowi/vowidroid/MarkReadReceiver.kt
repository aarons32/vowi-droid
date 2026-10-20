package com.vowi.vowidroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** "Mark as read" on a text notification. */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        if (deviceId.isEmpty() || peer.isEmpty()) return
        val pending = goAsync()
        thread(name = "vowi-mark-read") {
            try {
                if (VowiCore.isStarted) VowiCore.markRead(deviceId, peer) else Notifications.cancelConversation(context, deviceId, peer)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_PEER = "peer"
    }
}
