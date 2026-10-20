package com.vowi.vowidroid.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Binder
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.vowi.vowidroid.R
import com.vowi.vowidroid.VowiCore
import com.vowi.vowidroid.data.Contacts
import com.vowi.vowidroid.data.Conversation
import com.vowi.vowidroid.ui.MainActivity
import com.vowi.vowidroid.ui.components.LetterTile

/** The rows of the conversations widget, read from VowiDroid's message store. */
class ConversationsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)

    private class Row(val conversation: Conversation, val name: String?, val avatar: Bitmap)

    private class Rows(private val context: Context) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()

        override fun onCreate() = Unit

        /** Runs on a binder thread, where reading the store and Contacts is fine. */
        override fun onDataSetChanged() {
            val identity = Binder.clearCallingIdentity()
            try {
                val size = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
                rows = VowiCore.recentConversations(context, LIMIT).filter { !it.archived }.map { conversation ->
                    val name = Contacts.nameFor(context, conversation.peer)
                    val photo = Contacts.photoFor(context, conversation.peer)
                    Row(conversation, name, if (photo != null) LetterTile.circle(photo, size) else LetterTile.bitmap(name, conversation.peer, size))
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_conversation_item)
            val row = rows.getOrNull(position) ?: return views
            val conversation = row.conversation
            val title = row.name ?: Contacts.format(conversation.peer)
            val snippet = if (conversation.outgoing) context.getString(R.string.messages_you_prefix) + " " + conversation.lastMessage else conversation.lastMessage
            val unread = conversation.unread > 0
            val time = conversation.lastTime?.toEpochMilli()?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            }
            views.setImageViewBitmap(R.id.widget_avatar, row.avatar)
            views.setTextViewText(R.id.widget_name, bold(title, unread))
            views.setTextViewText(R.id.widget_snippet, bold(snippet.replace('\n', ' '), unread))
            views.setTextViewText(R.id.widget_time, time ?: "")
            // One spoken line per row: who, how many unread, the last text and when.
            val spoken = listOfNotNull(
                title,
                if (unread) context.resources.getQuantityString(R.plurals.a11y_unread, conversation.unread, conversation.unread) else null,
                if (conversation.outgoing) context.getString(R.string.a11y_you_said, conversation.lastMessage) else conversation.lastMessage,
                time?.toString(),
            ).joinToString(". ")
            views.setContentDescription(R.id.widget_item, spoken)
            views.setOnClickFillInIntent(
                R.id.widget_item,
                Intent()
                    .putExtra(MainActivity.EXTRA_THREAD_DEVICE, conversation.deviceId)
                    .putExtra(MainActivity.EXTRA_THREAD_PEER, conversation.peer),
            )
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long =
            rows.getOrNull(position)?.conversation?.let { "${it.deviceId}|${it.peer}".hashCode().toLong() } ?: position.toLong()

        override fun hasStableIds(): Boolean = true

        private fun bold(text: String, bold: Boolean): CharSequence {
            if (!bold) return text
            return SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private companion object {
        const val LIMIT = 30
    }
}
