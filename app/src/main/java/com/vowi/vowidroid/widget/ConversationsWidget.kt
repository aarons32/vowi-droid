package com.vowi.vowidroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.vowi.vowidroid.R
import com.vowi.vowidroid.ui.MainActivity

/**
 * The home-screen list of recent conversations, after AOSP Messaging's
 * conversation-list widget: tap a conversation to open it, or the pencil to
 * start a new one.
 */
// notifyAppWidgetViewDataChanged and setRemoteAdapter are deprecated in favour of
// RemoteCollectionItems, which needs Android 12; VowiDroid supports Android 8.
@Suppress("DEPRECATION")
class ConversationsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, it)) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    private fun views(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_conversations)
        val adapter = Intent(context, ConversationsWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Each widget gets its own adapter intent, as RemoteViewsService tells them apart by it.
        adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.widget_list, adapter)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setOnClickPendingIntent(R.id.widget_title, open(context, 1, Intent().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MESSAGES)))
        views.setOnClickPendingIntent(R.id.widget_compose, open(context, 2, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))
        // Rows fill in which conversation to open; the template must be mutable for that.
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(
                context, 3,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )
        return views
    }

    private fun open(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            intent.setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** Redraws every conversations widget, e.g. after a text arrives or is read. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, ConversationsWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }
}
