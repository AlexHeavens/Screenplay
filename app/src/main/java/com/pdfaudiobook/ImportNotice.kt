/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the import notification says: the book being made now, its stage and progress (e.g. "Reading 41%"), and whether the import is [paused],
 * in which case the action is Resume instead of Pause.
 */
data class ImportNotice(val title: String, val text: String, val paused: Boolean) {
    companion object {
        const val PAUSE = "com.pdfaudiobook.action.PAUSE_IMPORT"
        const val RESUME = "com.pdfaudiobook.action.RESUME_IMPORT"
        const val CHANNEL = "generate"
        const val TITLE_NONE = "Making an audiobook"

        /** The notice for [books]: the first of [ImportQueue.line] is the book being made (or the next to be, when none runs). */
        fun of(books: List<Book>, paused: Boolean): ImportNotice {
            val book = ImportQueue.line(books).firstOrNull() ?: return ImportNotice(TITLE_NONE, if (paused) "Paused" else "", paused)
            val stage = Stages.of(book, null).lastOrNull { it.state == StageState.ONGOING }
            val where = when {
                stage == null -> "Waiting"
                stage.fraction == null -> stage.kind.label
                else -> "${stage.kind.label} ${(stage.fraction * 100).toInt().coerceIn(0, 100)}%"
            }
            return ImportNotice(book.title, if (paused) "Paused · $where" else where, paused)
        }

        fun build(ctx: Context, notice: ImportNotice): Notification {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Audiobook generation", NotificationManager.IMPORTANCE_LOW))
            val action = if (notice.paused) RESUME else PAUSE
            val intent = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ImportControlReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val button = Notification.Action.Builder(Icon.createWithResource(ctx, if (notice.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause), if (notice.paused) "Resume" else "Pause", intent).build()
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .addAction(button)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Posts a new notification under [id] each time what it says changes, until cancelled. */
        suspend fun keepUpdated(ctx: Context, id: Int) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            combine(Library.books, ImportQueue.paused) { books, paused -> of(ImportQueue.showStoppingAsWaiting(books), paused) }
                .distinctUntilChanged()
                .collect { nm.notify(id, build(ctx, it)) }
        }
    }
}

/** Receives the notification's Pause and Resume buttons. Only the running import's worker keeps the notification up, so the process is alive. */
class ImportControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ImportNotice.PAUSE -> ImportQueue.pause()
            ImportNotice.RESUME -> ImportQueue.resume()
        }
    }
}
