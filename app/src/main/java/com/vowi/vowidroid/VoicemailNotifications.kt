package com.vowi.vowidroid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vowi.vowidroid.core.ims.VoicemailState
import com.vowi.vowidroid.ui.MainActivity

/** "New voicemail" with Call voicemail, as AOSP's dialer shows message waiting; gone once the box is empty. */
object VoicemailNotifications {
    private const val BASE_ID = 40

    @SuppressLint("MissingPermission") // posting is skipped when notifications are off
    fun update(context: Context, lineId: String, lineName: String, state: VoicemailState) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        val id = BASE_ID + lineId.hashCode()
        if (!state.waiting) {
            manager.cancel(id)
            return
        }
        if (!manager.areNotificationsEnabled()) {
            PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, R.string.denial_voicemail_notification)
            return
        }
        val title = state.newMessages?.takeIf { it > 1 }?.let { app.resources.getQuantityString(R.plurals.voicemail_new, it, it) }
            ?: app.getString(R.string.voicemail_new_one)
        val call = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_VOICEMAIL_LINE, lineId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationChannels.ensureLine(app, lineId, lineName)
        val notification = NotificationCompat.Builder(app, NotificationChannels.id(NotificationChannels.Kind.VOICEMAIL, lineId))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lineName.ifEmpty { null })
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setContentIntent(call)
            .addAction(0, app.getString(R.string.action_call_voicemail), call)
            .build()
        manager.notify(id, notification)
    }
}
