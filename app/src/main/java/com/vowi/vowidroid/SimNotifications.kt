package com.vowi.vowidroid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SimLocation
import com.vowi.vowidroid.ui.MainActivity

/**
 * "New SIM card": a card no line owns was put into a slot or reader. Each
 * card is announced once; the core decides what counts as put in.
 */
object SimNotifications {
    private const val PREFERENCES = "sim_cards"
    private const val KEY_ANNOUNCED = "announced"
    private const val ID_BASE = 0x51c0

    @Synchronized
    fun announce(context: Context, cards: List<FoundCard>, describe: (SimLocation) -> String) {
        val known = announced(context)
        val fresh = cards.filter { it.iccid.isNotEmpty() && it.iccid !in known }
        if (fresh.isEmpty()) return
        fresh.forEach { markKnown(context, it.iccid) }
        fresh.forEach { post(context, it, describe(it.location)) }
    }

    /** A card VowiDroid should not announce, e.g. one whose line was just removed. */
    @Synchronized
    fun markKnown(context: Context, card: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().putStringSet(KEY_ANNOUNCED, announced(context) + card).apply()
    }

    private fun announced(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet(KEY_ANNOUNCED, null)?.toSet().orEmpty()

    fun cancel(context: Context, card: String) {
        NotificationManagerCompat.from(context).cancel(ID_BASE + card.hashCode())
    }

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled
    private fun post(context: Context, card: FoundCard, place: String) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) {
            PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, R.string.denial_sim_notification)
            return
        }
        val open = PendingIntent.getActivity(
            app, card.iccid.hashCode(),
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ADD_LINE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = card.label.ifEmpty { app.getString(R.string.sim_card_default_name) }
        val notification = NotificationCompat.Builder(app, NotificationChannels.SIM_CARDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.sim_new_title))
            .setContentText(app.getString(R.string.sim_new_body, name, place))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.sim_new_body, name, place)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, app.getString(R.string.add_line_action), open)
            .build()
        manager.notify(ID_BASE + card.iccid.hashCode(), notification)
    }
}
