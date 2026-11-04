package com.voluene.monobrowser

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.voluene.monobrowser.browser.IncomingIntent

/**
 * Home screen search bar (like other browsers' widgets): the bar opens a new tab with the keyboard up, the lock
 * a private one. Static content, so it is only (re)built when placed or resized; no periodic updates.
 */
class SearchWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(R.id.widget_search, open(context, IncomingIntent.ACTION_SEARCH, REQUEST_SEARCH))
            setOnClickPendingIntent(R.id.widget_private, open(context, IncomingIntent.ACTION_SEARCH_PRIVATE, REQUEST_PRIVATE))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, action: String, request: Int): PendingIntent = PendingIntent.getActivity(
        context, request,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_SEARCH = 1
        const val REQUEST_PRIVATE = 2
    }
}
