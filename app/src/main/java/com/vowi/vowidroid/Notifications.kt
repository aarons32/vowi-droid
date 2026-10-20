package com.vowi.vowidroid

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.vowi.vowidroid.data.Message
import com.vowi.vowidroid.ui.BubbleActivity
import com.vowi.vowidroid.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap

/** Notifications for events from the core. Posting is safe from any thread. */
object Notifications {
    private const val TAG = "VowiNotify"

    /** What each conversation's notification last showed, so a bubble can be re-posted quietly. */
    private class Posted(val deviceId: String, val lineName: String, val sender: String, val unread: List<Message>)

    private val posted = ConcurrentHashMap<Int, Posted>()

    /**
     * A conversation's unread texts, with the sender's contact name and photo,
     * an inline reply that goes back through the same line, and Mark as read.
     * Call off the main thread: it looks the sender up in Contacts.
     */
    fun sms(context: Context, deviceId: String, lineName: String, sender: String, unread: List<Message>) =
        sms(context, deviceId, lineName, sender, unread, quiet = false)

    /**
     * [quiet] re-posts a bubbled conversation without a sound and without
     * its entry in the shade, so reading it keeps the bubble on screen.
     */
    private fun sms(context: Context, deviceId: String, lineName: String, sender: String, unread: List<Message>, quiet: Boolean) {
        if (unread.isEmpty()) return
        val app = context.applicationContext
        val id = conversationId(deviceId, sender)
        val person = ConversationShortcuts.person(app, sender)
        // The conversation's shortcut puts the notification in the shade's conversation section,
        // with per-conversation settings and bubbles (Android 11+).
        val shortcutId = ConversationShortcuts.id(deviceId, sender).takeIf { ConversationShortcuts.report(app, deviceId, sender) }
        val reply = RemoteInput.Builder(ReplyReceiver.EXTRA_TEXT).setLabel(app.getString(R.string.action_reply)).build()
        val replyIntent = PendingIntent.getBroadcast(
            app, id,
            Intent(app, ReplyReceiver::class.java)
                .putExtra(ReplyReceiver.EXTRA_DEVICE, deviceId)
                .putExtra(ReplyReceiver.EXTRA_PEER, sender),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val markReadIntent = PendingIntent.getBroadcast(
            app, id,
            Intent(app, MarkReadReceiver::class.java)
                .putExtra(MarkReadReceiver.EXTRA_DEVICE, deviceId)
                .putExtra(MarkReadReceiver.EXTRA_PEER, sender),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(app.getString(R.string.sms_me)).build())
            .setGroupConversation(false)
        for (message in unread) style.addMessage(message.body, message.time?.toEpochMilli() ?: System.currentTimeMillis(), person)
        NotificationChannels.ensureLine(app, deviceId, lineName)
        val builder = NotificationCompat.Builder(app, NotificationChannels.id(NotificationChannels.Kind.MESSAGES, deviceId))
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setOnlyAlertOnce(quiet)
            // Which line the text came in on, when there is more than one.
            .setSubText(lineName.ifEmpty { null })
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openThread(app, id, deviceId, sender))
            .addAction(
                NotificationCompat.Action.Builder(0, app.getString(R.string.action_reply), replyIntent)
                    .addRemoteInput(reply)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(0, app.getString(R.string.action_mark_read), markReadIntent)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        if (shortcutId != null) {
            builder.setShortcutId(shortcutId).setLocusId(LocusIdCompat(shortcutId))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setBubbleMetadata(
                    NotificationCompat.BubbleMetadata.Builder(
                        BubbleActivity.pendingIntent(app, deviceId, sender),
                        person.icon ?: IconCompat.createWithResource(app, R.drawable.ic_notification),
                    )
                        .setDesiredHeight(600)
                        .setSuppressNotification(quiet)
                        .build(),
                )
            }
        }
        posted[id] = Posted(deviceId, lineName, sender, unread)
        post(app, id, builder.build())
    }

    /**
     * Takes a conversation's notification away, e.g. once it is read. A
     * conversation showing as a bubble keeps its bubble: Android removes the
     * bubble with its notification, so that one is re-posted quietly instead.
     */
    fun cancelConversation(context: Context, deviceId: String, peer: String, keepBubble: Boolean = true) {
        val id = conversationId(deviceId, peer)
        val last = posted[id]
        if (keepBubble && last != null && isBubble(context, id)) {
            Thread({ sms(context, last.deviceId, last.lineName, last.sender, last.unread, quiet = true) }, "vowi-bubble").start()
            return
        }
        posted.remove(id)
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun isBubble(context: Context, id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.activeNotifications.any { it.id == id && it.notification.flags and Notification.FLAG_BUBBLE != 0 } }.getOrDefault(false)
    }

    private fun conversationId(deviceId: String, peer: String): Int = "$deviceId|$peer".hashCode()

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled
    private fun post(app: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(app)
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        } else {
            Log.i(TAG, "notifications are disabled; event not shown")
            PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, R.string.denial_text_notification)
        }
    }

    private fun openThread(app: Context, requestCode: Int, deviceId: String, peer: String): PendingIntent =
        PendingIntent.getActivity(
            app, requestCode,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_THREAD_DEVICE, deviceId)
                .putExtra(MainActivity.EXTRA_THREAD_PEER, peer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
