package com.vowi.vowidroid.telecom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.vowi.vowidroid.AppPermissions
import com.vowi.vowidroid.NotificationChannels
import com.vowi.vowidroid.PermissionDenials
import com.vowi.vowidroid.R
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.ui.MainActivity
import kotlin.concurrent.thread

/**
 * Call notifications. A self-managed VoIP app rings and shows incoming calls
 * itself: a full-screen in-call screen when the phone is locked, a heads-up
 * answer/decline card otherwise.
 */
object CallNotifications {
    const val INCOMING_ID = 10
    private const val MISSED_ID = 11

    /**
     * Shows a ringing call on its line's Incoming calls category. A call that
     * waits behind another one does not ring insistently; the call-waiting
     * tone plays in the call instead. [line] names the line when there are
     * several.
     */
    @SuppressLint("MissingPermission") // posting is skipped when notifications are off
    fun showIncoming(context: Context, deviceId: String, number: String, name: String?, waiting: Boolean, line: String? = null) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // Without notifications the screen is the only way to see the call.
            PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, R.string.denial_call_notification)
            context.startActivity(InCallActivity.intent(context))
            return
        }
        val caller = person(context, number, name)
        val fullScreen = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context, 1, InCallActivity.intent(context, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context, 2, CallActionReceiver.intent(context, CallActionReceiver.ACTION_DECLINE), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationChannels.ensureLine(context, deviceId, line)
        val notification = NotificationCompat.Builder(context, NotificationChannels.id(NotificationChannels.Kind.INCOMING_CALLS, deviceId))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(caller.name)
            .setContentText(if (line != null) context.getString(R.string.call_incoming_via, line) else context.getString(R.string.call_incoming_title))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
        // Keep ringing until the call is answered, declined or gone.
        if (!waiting) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(INCOMING_ID, notification)
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
    }

    /** The foreground notification while a call is up. */
    fun ongoing(context: Context, state: CallManager.CallState): Notification {
        val open = PendingIntent.getActivity(
            context, 3, InCallActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getBroadcast(
            context, 4, CallActionReceiver.intent(context, CallActionReceiver.ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(context, state.number, if (state.conference) context.getString(R.string.incall_conference) else state.name),
                    hangUp,
                ),
            )
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        state.connectedAt?.let { builder.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        return builder.build()
    }

    /**
     * A missed call with the caller's name and photo, and Call back and
     * Message buttons as AOSP's Dialer shows them. AOSP's own missed-call
     * notifications skip self-managed calls, so VowiDroid posts its own.
     */
    fun showMissed(context: Context, deviceId: String, number: String, name: String?) {
        val app = context.applicationContext
        // Contacts lookups stay off the main thread.
        thread(name = "vowi-missed-call") { postMissed(app, deviceId, number, name ?: Contacts.nameFor(app, number)) }
    }

    @SuppressLint("MissingPermission") // posting is skipped when notifications are off
    private fun postMissed(app: Context, deviceId: String, number: String, name: String?) {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) {
            PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, R.string.denial_missed_call_notification)
            return
        }
        val id = missedId(deviceId, number)
        val open = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CALLS)
                .putExtra(MainActivity.EXTRA_MISSED_DEVICE, deviceId)
                .putExtra(MainActivity.EXTRA_MISSED_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationChannels.ensureLine(app, deviceId)
        val builder = NotificationCompat.Builder(app, NotificationChannels.id(NotificationChannels.Kind.MISSED_CALLS, deviceId))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.call_missed_title))
            .setContentText(name ?: Contacts.format(number).ifEmpty { app.getString(R.string.call_unknown_caller) })
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        Contacts.photoFor(app, number)?.let { builder.setLargeIcon(it) }
        if (number.isNotBlank()) {
            val callBack = PendingIntent.getActivity(
                app, id + 1,
                Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_CALL_BACK_DEVICE, deviceId)
                    .putExtra(MainActivity.EXTRA_CALL_BACK_NUMBER, number)
                    .putExtra(MainActivity.EXTRA_MISSED_DEVICE, deviceId)
                    .putExtra(MainActivity.EXTRA_MISSED_NUMBER, number)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val message = PendingIntent.getActivity(
                app, id + 2,
                Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_THREAD_DEVICE, deviceId)
                    .putExtra(MainActivity.EXTRA_THREAD_PEER, number)
                    .putExtra(MainActivity.EXTRA_MISSED_DEVICE, deviceId)
                    .putExtra(MainActivity.EXTRA_MISSED_NUMBER, number)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, app.getString(R.string.action_call_back), callBack)
            builder.addAction(0, app.getString(R.string.action_message), message)
        }
        manager.notify(id, builder.build())
    }

    fun cancelMissed(context: Context, deviceId: String, number: String) {
        NotificationManagerCompat.from(context).cancel(missedId(deviceId, number))
    }

    private fun missedId(deviceId: String, number: String): Int = MISSED_ID + "$deviceId|$number".hashCode()

    private fun person(context: Context, number: String, name: String?): Person =
        Person.Builder()
            .setName(name ?: Contacts.format(number).ifEmpty { context.getString(R.string.call_unknown_caller) })
            .setImportant(true)
            .build()
}
