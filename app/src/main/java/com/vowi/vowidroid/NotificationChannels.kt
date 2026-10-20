package com.vowi.vowidroid

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.vowi.vowidroid.data.LineStore

/**
 * VowiDroid's notification categories, as Android's settings list them: one
 * group per line holding that line's incoming calls, missed calls, messages
 * and voicemail, so each number can have its own sounds and importance; and
 * app-wide categories for the line status, the call in progress and new SIM
 * cards.
 *
 * The line-status category backs the row in Settings. Only the user can turn
 * it off, on Android's page for it: whenever a foreground service posts on a
 * category at "min" or "none", the system raises the category back to "low"
 * unless the user set its importance themselves, and an app may never delete
 * the category its foreground notification is on.
 */
object NotificationChannels {
    /** The service's "<line> online" notification. */
    const val STATUS = "status"
    const val ONGOING_CALL = "ongoing_call"
    const val SIM_CARDS = "sim_cards"

    /** What each line has a category for, in the order the line's page lists them. */
    enum class Kind(val key: String, val title: Int, val description: Int, val importance: Int) {
        INCOMING_CALLS("incoming_calls", R.string.channel_incoming_calls, R.string.channel_incoming_calls_description, NotificationManager.IMPORTANCE_HIGH),
        MISSED_CALLS("missed_calls", R.string.channel_missed_calls, R.string.channel_missed_calls_description, NotificationManager.IMPORTANCE_HIGH),
        MESSAGES("messages", R.string.channel_messages, R.string.channel_messages_description, NotificationManager.IMPORTANCE_HIGH),
        VOICEMAIL("voicemail", R.string.channel_voicemail, R.string.channel_voicemail_description, NotificationManager.IMPORTANCE_DEFAULT),
    }

    /** How a category stands for the user: alerting, silent, or off (itself, its line's group, or the app). */
    enum class State { ON, SILENT, OFF }

    private const val PREFERENCES = "notification_channels"
    private const val KEY_LEGACY_REMOVED = "legacy_removed"
    private const val GROUP_PREFIX = "line."

    /** The app-wide categories of earlier builds, replaced by the per-line ones and the status one. */
    private val legacy = listOf("service", "messages", "calls", "incoming_calls", "voicemail")

    /** The lines last pushed to Android, so a lines update that changes nothing costs no calls. */
    private var synced: Map<String, String>? = null

    fun groupId(lineId: String): String = GROUP_PREFIX + lineId

    fun id(kind: Kind, lineId: String): String = "${kind.key}.$lineId"

    /** The line a group belongs to, or null for a group that is not a line's. */
    fun lineOf(groupId: String): String? =
        groupId.takeIf { it.startsWith(GROUP_PREFIX) }?.removePrefix(GROUP_PREFIX)?.takeIf { it.isNotEmpty() }

    /** Creates the app-wide categories and every saved line's; run once at app start. */
    fun install(context: Context) {
        val manager = manager(context)
        val preferences = preferences(context)
        if (!preferences.getBoolean(KEY_LEGACY_REMOVED, false)) {
            legacy.forEach { manager.deleteNotificationChannel(it) }
            preferences.edit().putBoolean(KEY_LEGACY_REMOVED, true).apply()
        }
        createAppWide(context)
        sync(context, LineStore(context).all().map { it.id to it.name })
    }

    /**
     * Names every category again in the app's current language. Creating a
     * category that exists changes only its name and description; the
     * user's sound, importance and lock-screen choices stay.
     */
    @Synchronized
    fun relabel(context: Context) {
        createAppWide(context)
        for (line in LineStore(context).all()) ensureLine(context, line.id, line.name)
    }

    private fun createAppWide(context: Context) {
        val manager = manager(context)
        val ongoing = NotificationChannel(ONGOING_CALL, context.getString(R.string.channel_ongoing_call), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_ongoing_call_description)
            setSound(null, null)
            setShowBadge(false)
        }
        val sims = NotificationChannel(SIM_CARDS, context.getString(R.string.channel_sim_cards), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_sim_cards_description)
        }
        val status = NotificationChannel(STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_status_description)
            setSound(null, null)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(status, ongoing, sims))
    }

    /** Keeps one group per line, named after it; the groups of lines that are gone go with their categories. */
    @Synchronized
    fun sync(context: Context, lines: List<Pair<String, String>>) {
        val wanted = lines.toMap()
        if (wanted == synced) return
        val manager = manager(context)
        for ((lineId, name) in wanted) if (synced?.get(lineId) != name) ensureLine(context, lineId, name)
        manager.notificationChannelGroups
            .filter { group -> lineOf(group.id).let { it != null && it !in wanted } }
            .forEach { group -> runCatching { manager.deleteNotificationChannelGroup(group.id) } }
        synced = wanted
    }

    /**
     * One line's group and categories, created or renamed; cheap enough to
     * call before every post, so a line's first text or call cannot land on a
     * missing category. Without [name] the group keeps its name, or takes
     * the saved line's.
     */
    fun ensureLine(context: Context, lineId: String, name: String? = null) {
        val manager = manager(context)
        val label = name?.takeIf { it.isNotBlank() }
            ?: manager.notificationChannelGroups.firstOrNull { it.id == groupId(lineId) }?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: lineName(context, lineId)
        manager.createNotificationChannelGroup(NotificationChannelGroup(groupId(lineId), label))
        manager.createNotificationChannels(Kind.entries.map { channel(context, it, lineId) })
    }

    /** A saved line's name, or its ID when it is not saved (any more). */
    fun lineName(context: Context, lineId: String): String =
        LineStore(context).all().firstOrNull { it.id == lineId }?.name?.takeIf { it.isNotBlank() } ?: lineId

    /** Whether the line-status notification shows: its category is on and VowiDroid may notify at all. */
    fun statusShown(context: Context): Boolean = state(context, STATUS) != State.OFF

    /** How a category stands now, for a settings row. */
    fun state(context: Context, channelId: String): State {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return State.OFF
        val manager = manager(context)
        val channel = manager.getNotificationChannel(channelId) ?: return State.OFF
        val group = channel.group
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && group != null && manager.getNotificationChannelGroup(group)?.isBlocked == true) return State.OFF
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> State.OFF
            channel.importance <= NotificationManager.IMPORTANCE_LOW -> State.SILENT
            else -> State.ON
        }
    }

    /** One category's page in Android's settings; false when the phone has none. */
    fun openChannel(context: Context, channelId: String): Boolean = open(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
    )

    /** The app's notification page in Android's settings, with every category. */
    fun openApp(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun channel(context: Context, kind: Kind, lineId: String): NotificationChannel =
        NotificationChannel(id(kind, lineId), context.getString(kind.title), kind.importance).apply {
            description = context.getString(kind.description)
            group = groupId(lineId)
            if (kind == Kind.INCOMING_CALLS) {
                // A self-managed calling app rings by itself: this category's sound is
                // the ringtone, looped by the incoming call's insistent notification.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
            }
        }

    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
