package com.vowi.vowidroid.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import com.vowi.vowidroid.ConversationShortcuts
import com.vowi.vowidroid.NotificationChannels
import android.widget.Toast
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vowi.vowidroid.R

/** Small actions shared by the call log, call details and conversations. */
object Actions {
    /** Copies [text]; Android 13 and later confirm on their own, older versions get a toast. */
    fun copy(context: Context, clipboard: ClipboardManager, text: String) {
        clipboard.setText(AnnotatedString(text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** Shares plain text through the system share sheet. */
    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, null))
    }

    /** Opens Contacts on a new contact with [number] filled in. */
    fun createContact(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        start(context, intent)
    }

    /** Opens Contacts to add [number] to a new or existing contact. */
    fun addContact(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        start(context, intent)
    }

    /** Opens a contact card. */
    fun viewContact(context: Context, lookupUri: Uri) {
        start(context, Intent(Intent.ACTION_VIEW, lookupUri))
    }

    /**
     * One conversation's notification settings: its sound, priority and
     * bubble (Android 11+). Settings shows the line's Messages category when
     * the conversation has no settings of its own yet.
     */
    fun conversationNotificationSettings(context: Context, deviceId: String, peer: String) {
        val app = context.applicationContext
        NotificationChannels.ensureLine(app, deviceId)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.id(NotificationChannels.Kind.MESSAGES, deviceId))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            start(app, intent)
            return
        }
        intent.putExtra(Settings.EXTRA_CONVERSATION_ID, ConversationShortcuts.id(deviceId, peer))
        // The conversation's shortcut must exist first; publishing it reads Contacts, so off the main thread.
        Thread({
            ConversationShortcuts.report(app, deviceId, peer)
            Handler(Looper.getMainLooper()).post { start(app, intent) }
        }, "vowi-shortcut").start()
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
        }
    }
}
