/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class StageKind(val label: String) { DOWNLOAD("Download"), READ("Reading"), SPEAK("Speaking"), ENCODE("Encoding") }

/** [NEXT] has not started but is the stage that starts next; [ONGOING] is running; [DONE] is over. */
enum class StageState(val description: String) { NEXT("next"), ONGOING("in progress"), DONE("done") }

/**
 * One stage of making a book, as its row shows it. An ongoing stage has [fraction] (null when its size is unknown, so the bar runs
 * without end) and [text], its percentage and its absolute total, e.g. "42% · 1,200/3,000 words"; the others have neither.
 */
data class ImportStage(val kind: StageKind, val state: StageState, val fraction: Float? = null, val text: String = "")

/** The stages a book in the import queue goes through, in order: fetching its file (only a book from "Find books"), reading, speaking, encoding. */
object Stages {
    /**
     * The stages to show for [book], [download] being its fetch in progress. Those over and the one running come first, then the first not
     * started; later ones stay hidden. Reading and speaking overlap, so both can run at once. A book whose import is over has none.
     */
    fun of(book: Book, download: DownloadProgress?): List<ImportStage> {
        if (!book.importing) return emptyList()
        val all = buildList {
            if (book.downloadUrl.isNotEmpty()) add(fetching(book, download))
            add(reading(book))
            add(speaking(book))
            add(encoding(book))
        }
        val next = all.indexOfFirst { it.state == StageState.NEXT }
        return if (next < 0) all else all.take(next + 1)
    }

    private fun percent(fraction: Float) = (fraction * 100).toInt().coerceIn(0, 100)

    private fun fetching(book: Book, download: DownloadProgress?) = when {
        download != null -> ImportStage(StageKind.DOWNLOAD, StageState.ONGOING, download.fraction, download.counts)
        book.awaitingDownload -> ImportStage(StageKind.DOWNLOAD, StageState.NEXT)
        else -> ImportStage(StageKind.DOWNLOAD, StageState.DONE)
    }

    private fun reading(book: Book) = when (book.status) {
        Status.QUEUED -> ImportStage(StageKind.READ, StageState.NEXT)
        Status.READING, Status.SPELLING -> {
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
        else -> ImportStage(StageKind.SPEAK, StageState.NEXT)
    }

    private fun encoding(book: Book): ImportStage {
        if (book.status != Status.ENCODING) return ImportStage(StageKind.ENCODE, StageState.NEXT)
        val fraction = ((book.progress - 0.9f) * 10f).coerceIn(0f, 1f)
        return ImportStage(StageKind.ENCODE, StageState.ONGOING, fraction, "${percent(fraction)}%")
    }
}

private fun icon(kind: StageKind): ImageVector = when (kind) {
    StageKind.DOWNLOAD -> Icons.Filled.KeyboardArrowDown
    StageKind.READ -> Icons.Filled.Search
    StageKind.SPEAK -> Icons.Filled.PlayArrow
    StageKind.ENCODE -> Icons.Filled.Settings
}

/**
 * The stages of [title] in a row: an icon each, grey when next, blue while running, white once done. A running stage also has a thin
 * bar and its percentage with its total. Each stage's icon (or, when running, its bar and text) carries a `stage-…` test tag.
 */
@Composable
fun StageStrip(title: String, stages: List<ImportStage>, modifier: Modifier = Modifier) {
    val grey = Color.Gray
    val blue = Color(0xFF4DA3FF)
    // White is the finished colour on the dark theme; on a light one it would vanish, so the text colour stands in.
    val done = if (isDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in stages) {
            val tint = when (s.state) { StageState.NEXT -> grey; StageState.ONGOING -> blue; StageState.DONE -> done }
            val name = "${s.kind.label} ${s.state.description}"
            if (s.state != StageState.ONGOING) {
                Icon(icon(s.kind), name, Modifier.size(20.dp).testTag("stage-${s.kind.name}-$title").semantics { stateDescription = s.state.description }, tint = tint)
            } else {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(icon(s.kind), name, Modifier.size(20.dp).testTag("stage-${s.kind.name}-$title").semantics { stateDescription = s.state.description }, tint = tint)
                        Text(s.text, Modifier.testTag("stage-text-${s.kind.name}-$title"), style = MaterialTheme.typography.labelSmall, color = blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val bar = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp).testTag("stage-bar-${s.kind.name}-$title")
                    val fraction = s.fraction
                    if (fraction == null) LinearProgressIndicator(bar, color = blue, trackColor = grey.copy(alpha = 0.3f))
                    else LinearProgressIndicator({ fraction }, bar, color = blue, trackColor = grey.copy(alpha = 0.3f))
                }
            }
        }
    }
}
