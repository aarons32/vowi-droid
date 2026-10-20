package com.vowi.vowidroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/** Sends a reply typed into an SMS notification through the line it arrived on. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_TEXT)?.toString()?.trim()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        if (text.isNullOrEmpty() || deviceId.isEmpty() || peer.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "vowi-reply") {
            val sent = try {
                VowiCore.sendSms(deviceId, peer, text)
                true
            } catch (e: Exception) {
                Log.w(TAG, "reply failed", e)
                false
            }
            // Replying reads the conversation.
            if (sent) runCatching { VowiCore.markRead(deviceId, peer) }
            ContextCompat.getMainExecutor(app).execute {
                Notifications.cancelConversation(app, deviceId, peer)
                if (!sent) Toast.makeText(app, R.string.reply_failed, Toast.LENGTH_LONG).show()
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VowiReply"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_PEER = "peer"
    }
}
