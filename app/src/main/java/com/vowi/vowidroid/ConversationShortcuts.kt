package com.vowi.vowidroid

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.ui.MainActivity
import com.vowi.vowidroid.ui.components.LetterTile

/**
 * A long-lived shortcut per conversation, as AOSP Messaging and Google
 * Messages publish them. Android builds on it for the notification shade's
 * conversation section, per-conversation notification settings and
 * priority, bubbles, and Direct Share targets in the share sheet.
 */
object ConversationShortcuts {
    private const val TAG = "VowiShortcuts"
    private const val PREFIX = "conversation|"

    /** The share-target category declared in res/xml/shortcuts.xml. */
    const val SHARE_CATEGORY = "com.vowi.vowidroid.category.SHARE_CONVERSATION"

    private const val ICON_SIZE = 192

    fun id(deviceId: String, peer: String): String = "$PREFIX$deviceId|$peer"

    /** The conversation a shortcut ID names, e.g. from Direct Share's EXTRA_SHORTCUT_ID. */
    fun parse(id: String?): Pair<String, String>? {
        if (id == null || !id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val deviceId = rest.substringBefore('|')
        val peer = rest.substringAfter('|', "")
        return if (deviceId.isEmpty() || peer.isEmpty()) null else deviceId to peer
    }

    /** The other person, with their contact name and photo, as notifications and shortcuts name them. */
    fun person(context: Context, peer: String): Person {
        val name = Contacts.nameFor(context, peer)
        val photo = Contacts.photoFor(context, peer)
        return Person.Builder()
            .setName(name ?: Contacts.format(peer))
            .setKey(peer)
            .setUri("tel:$peer")
            .setIcon(icon(name, peer, photo))
            .build()
    }

    /**
     * Publishes the conversation's shortcut, or brings it up to date and
     * ranks it as just used. Call off the main thread: it reads Contacts.
     */
    fun report(context: Context, deviceId: String, peer: String): Boolean {
        val app = context.applicationContext
        val person = person(app, peer)
        val id = id(deviceId, peer)
        val label = person.name?.toString().orEmpty().ifEmpty { peer }
        val shortcut = ShortcutInfoCompat.Builder(app, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(person.icon ?: icon(null, peer, null))
            .setPerson(person)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setCategories(setOf(SHARE_CATEGORY))
            .setIntent(
                Intent(app, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_THREAD_DEVICE, deviceId)
                    .putExtra(MainActivity.EXTRA_THREAD_PEER, peer),
            )
            .build()
        return runCatching { ShortcutManagerCompat.pushDynamicShortcut(app, shortcut) }
            .onFailure { Log.w(TAG, "conversation shortcut not published", it) }
            .isSuccess
    }

    /** Takes a deleted or blocked conversation out of the launcher and the share sheet. */
    fun remove(context: Context, deviceId: String, peer: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context.applicationContext, listOf(id(deviceId, peer))) }
            .onFailure { Log.w(TAG, "conversation shortcut not removed", it) }
    }

    private fun icon(name: String?, peer: String, photo: android.graphics.Bitmap?): IconCompat =
        IconCompat.createWithBitmap(if (photo != null) LetterTile.circle(photo, ICON_SIZE) else LetterTile.bitmap(name, peer, ICON_SIZE))
}
