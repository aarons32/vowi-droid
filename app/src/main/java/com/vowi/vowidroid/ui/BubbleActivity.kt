package com.vowi.vowidroid.ui

import com.vowi.vowidroid.AppLanguage
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vowi.vowidroid.ui.messages.ThreadScreen
import com.vowi.vowidroid.ui.theme.VowiTheme

/**
 * One conversation in a bubble (Android 11+). A bubble needs an activity it
 * can embed, which MainActivity, a single task, is not; anything that leaves
 * the conversation opens the full app instead.
 */
class BubbleActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty()
        if (deviceId.isEmpty() || peer.isEmpty()) {
            finish()
            return
        }
        setContent {
            VowiTheme {
                ThreadScreen(
                    deviceId = deviceId,
                    peer = peer,
                    onBack = ::finish,
                    onForward = { body -> openApp(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)) },
                    onOpenThread = { device, number -> openThread(device, number) },
                    onSwitchLine = { device -> openThread(device, peer) },
                    inBubble = true,
                )
            }
        }
    }

    private fun openThread(deviceId: String, peer: String) = openApp(
        Intent().putExtra(MainActivity.EXTRA_THREAD_DEVICE, deviceId).putExtra(MainActivity.EXTRA_THREAD_PEER, peer),
    )

    private fun openApp(intent: Intent) {
        startActivity(intent.setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val EXTRA_DEVICE = "com.vowi.vowidroid.BUBBLE_DEVICE"
        private const val EXTRA_PEER = "com.vowi.vowidroid.BUBBLE_PEER"

        /** The bubble's content; Android requires a mutable PendingIntent for it. */
        fun pendingIntent(context: Context, deviceId: String, peer: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                "bubble|$deviceId|$peer".hashCode(),
                Intent(context, BubbleActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    // A distinct data URI per conversation, so each bubble keeps its own intent.
                    .setData(Uri.fromParts("vowi-bubble", "$deviceId|$peer", null))
                    .putExtra(EXTRA_DEVICE, deviceId)
                    .putExtra(EXTRA_PEER, peer),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
