/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class StageKind(val label: String) { DOWNLOAD("Download"), READ("Reading"), SPEAK("Speaking"), ENCODE("Encoding"), LISTEN("Listened") }

/** [NEXT] has not started but is the stage that starts next; [ONGOING] is running; [DONE] is over. */
enum class StageState(val description: String) { NEXT("next"), ONGOING("in progress"), DONE("done") }

/**
 * One stage of making a book, as its row shows it. A stage with [text] (its percentage and its absolute total, e.g. "42% · 1,200/3,000 words")
 * also has a bar: [fraction] is how full it is, null when its size is unknown, so the bar runs without end. A stage waiting has neither.
 */
data class ImportStage(val kind: StageKind, val state: StageState, val fraction: Float? = null, val text: String = "")

/**
 * The stages a book in the import queue goes through, in order: fetching its file (done at once for a file the reader picked), reading, speaking, encoding.
 * An imported book has one: how far it has been listened to, with a bar and text but no icon.
 */
object Stages {
    /**
     * The stages to show for [book], [download] being its fetch in progress. Those over and the one running come first, then the first not
     * started; later ones stay hidden. Reading and speaking overlap, so both can run at once. A ready book has its listening; a failed one none.
     */
    fun of(book: Book, download: DownloadProgress?, chapter: String? = null): List<ImportStage> {
        if (book.status == Status.READY) return listOf(listened(book, chapter))
        if (!book.importing) return emptyList()
        val all = buildList {
            add(fetching(book, download))
            add(reading(book))
            add(speaking(book))
            add(encoding(book))
        }
        val next = all.indexOfFirst { it.state == StageState.NEXT }
        return if (next < 0) all else all.take(next + 1)
    }

    /** Time listened over the book's length and the [chapter] reached, e.g. "42% · 1:24/3:20 · Chapter 3"; a book never played reads 0%, a finished one its whole length. */
    private fun listened(book: Book, chapter: String?): ImportStage {
        val at = if (book.finished) book.durationMs else (book.positionMs ?: 0L).coerceIn(0L, book.durationMs)
        val fraction = (book.playedPercent ?: 0) / 100f
        return ImportStage(StageKind.LISTEN, StageState.DONE, fraction, "${percent(fraction)}% · ${clock(at)}/${clock(book.durationMs)}" + (chapter?.let { " · $it" } ?: ""))
    }

    private fun percent(fraction: Float) = (fraction * 100).toInt().coerceIn(0, 100)

    private fun fetching(book: Book, download: DownloadProgress?) = when {
        download != null -> ImportStage(StageKind.DOWNLOAD, StageState.ONGOING, download.fraction, download.counts)
        book.awaitingDownload -> ImportStage(StageKind.DOWNLOAD, StageState.NEXT)
        // A file the reader picked is on the device already, so its fetch counts as done.
        else -> ImportStage(StageKind.DOWNLOAD, StageState.DONE)
    }

    /**
     * A book put back in the queue after its text was all read ([Book.wordsTotal] is known) has been through reading and is waiting to speak again,
     * keeping the words it had. Its status says only QUEUED, so its stage comes from that progress, not from the status.
     */
    private val Book.stoppedWhileSpeaking get() = status == Status.QUEUED && wordsTotal > 0

    private fun reading(book: Book) = when {
        book.stoppedWhileSpeaking -> ImportStage(StageKind.READ, StageState.DONE)
        book.status == Status.QUEUED -> ImportStage(StageKind.READ, StageState.NEXT)
        book.status == Status.READING || book.status == Status.SPELLING -> {
            val fraction = book.readProgress
            ImportStage(StageKind.READ, StageState.ONGOING, fraction, "${percent(fraction)}% · ${book.pages}/${book.pagesTotal} pages")
        }
        else -> ImportStage(StageKind.READ, StageState.DONE)
    }

    private fun speaking(book: Book) = when {
        book.status == Status.SPEAKING -> {
            val fraction = book.speechProgress
            ImportStage(StageKind.SPEAK, StageState.ONGOING, fraction, "${percent(fraction)}% · ${"%,d".format(book.words)}/${"%,d".format(book.wordsTotal)} words")
        }
        // Until the last page is read the total is not known, so the share is an estimate and only the words spoken are exact.
        book.status == Status.READING && book.words > 0 -> {
            val fraction = book.speechProgress
            ImportStage(StageKind.SPEAK, StageState.ONGOING, fraction, "${percent(fraction)}% · ${"%,d".format(book.words)} words")
        }
        book.status == Status.ENCODING -> ImportStage(StageKind.SPEAK, StageState.DONE)
        // Waiting to carry on: the words it had are shown, though it is not running.
        book.stoppedWhileSpeaking -> {
            val fraction = (book.words.toFloat() / book.wordsTotal).coerceIn(0f, 1f)
            ImportStage(StageKind.SPEAK, StageState.NEXT, fraction, "${percent(fraction)}% · ${"%,d".format(book.words)}/${"%,d".format(book.wordsTotal)} words")
        }
        else -> ImportStage(StageKind.SPEAK, StageState.NEXT)
    }

    private fun encoding(book: Book): ImportStage {
        if (book.status != Status.ENCODING) return ImportStage(StageKind.ENCODE, StageState.NEXT)
        val fraction = ((book.progress - 0.9f) * 10f).coerceIn(0f, 1f)
        return ImportStage(StageKind.ENCODE, StageState.ONGOING, fraction, "${percent(fraction)}%")
    }
}

private fun icon(kind: StageKind): ImageVector? = when (kind) {
    StageKind.DOWNLOAD -> StageIcons.ArrowDown
    StageKind.READ -> Icons.Filled.Search
    StageKind.SPEAK -> StageIcons.Speaker
    StageKind.ENCODE -> StageIcons.MusicNote
    StageKind.LISTEN -> null
}

/**
 * The stages of [title] in a row. Above, the percentage text and a thin bar of each stage that has them (more than one when reading and speaking
 * run together); below, an icon for every stage, grey when next, blue while running, white once done. Tags: `stage-<KIND>-<title>` for an icon,
 * `stage-text-…` and `stage-bar-…` for the text and the bar.
 */
@Composable
fun StageStrip(title: String, stages: List<ImportStage>, modifier: Modifier = Modifier) {
    val grey = Color.Gray
    val blue = Color(0xFF4DA3FF)
    // White is the finished colour on the dark theme; on a light one it would vanish, so the text colour stands in.
    val done = if (isDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (s in stages.filter { it.text.isNotEmpty() }) {
            val tag = "${s.kind.name}-$title"
            Text(s.text, Modifier.testTag("stage-text-$tag"), style = MaterialTheme.typography.labelSmall, color = blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bar = Modifier.fillMaxWidth().height(3.dp).testTag("stage-bar-$tag").semantics { contentDescription = s.kind.label }
            val fraction = s.fraction
            if (fraction == null) LinearProgressIndicator(bar, color = blue, trackColor = grey.copy(alpha = 0.3f))
            else LinearProgressIndicator({ fraction }, bar, color = blue, trackColor = grey.copy(alpha = 0.3f))
        }
        // Listening has no icon: its bar and text say it.
        val icons = stages.filter { icon(it.kind) != null }
        if (icons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in icons) {
                val tint = when (s.state) { StageState.NEXT -> grey; StageState.ONGOING -> blue; StageState.DONE -> done }
                Icon(icon(s.kind)!!, "${s.kind.label} ${s.state.description}", Modifier.size(20.dp).testTag("stage-${s.kind.name}-$title").semantics { stateDescription = s.state.description }, tint = tint)
            }
        }
    }
}
