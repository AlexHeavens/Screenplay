/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Find books": searches Project Gutenberg's catalogue ([Gutendex]) and adds a tapped book to the import queue as an EPUB.
 * Nothing is requested until the reader searches or picks a genre; a tap sends no request itself, the queue fetches the file ([QueueDownload]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindBooks(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { Gutendex.client(ctx) }
    var text by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<Gutendex.Genre?>(null) }
    var popularity by remember { mutableStateOf(Gutendex.Popularity.ANY) }
    var englishOnly by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<Gutendex.Entry>>(emptyList()) }
    var lastPage by remember { mutableStateOf<Gutendex.Page?>(null) }
    var pageNumber by remember { mutableStateOf(1) }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<Int>()) }
    BackHandler(onBack = onBack)

    fun search(query: Gutendex.Query) {
        if (loading) return
        loading = true
        message = null
        scope.launch {
            try {
                val page = client.search(query)
                entries = if (query.page == 1) page.entries else entries + page.entries
                lastPage = page
                pageNumber = query.page
                searched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "The search failed."
            } finally {
                loading = false
            }
        }
    }
    fun newSearch(text: String = "", genre: Gutendex.Genre? = null, englishOnly: Boolean = true) = search(Gutendex.Query(text, genre, englishOnly))

    fun add(entry: Gutendex.Entry) {
        val url = entry.epubUrl
        if (url == null) { message = "“${entry.title}” has no EPUB, so it cannot be added."; return }
        if (entry.id in added) return
        // The book joins the queue at once; the queue fetches its file ([QueueDownload]), so taps never make parallel requests.
        added = added + entry.id
        Library.enqueueDownload(ctx, entry.title, "gutenberg:${entry.id}", url)
        message = "Added “${entry.title}” to the import queue."
    }

    val shown = Gutendex.filter(entries, popularity)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Find books") },
            navigationIcon = { IconButton(onBack, Modifier.testTag("find-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f).testTag("find-query"), singleLine = true, label = { Text("Title or author") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { newSearch(text, genre, englishOnly) }),
                )
                Button({ newSearch(text, genre, englishOnly) }, Modifier.testTag("find-search"), enabled = !loading) { Text("Search") }
            }
            Text("Genre", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Gutendex.Genre.values().toList()) { g ->
                    FilterChip(genre == g, { genre = if (genre == g) null else g; newSearch(text, genre, englishOnly) }, { Text(g.label) }, Modifier.testTag("genre-${g.name}"), shape = ControlShape, colors = themeChipColors())
                }
            }
            Text("Popularity (downloads)", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Gutendex.Popularity.values()) {
                    FilterChip(popularity == p, { popularity = p }, { Text(if (p.minDownloads == 0) p.label else "${p.label} (${"%,d".format(p.minDownloads)}+)") }, Modifier.testTag("popularity-${p.name}"), shape = ControlShape, colors = themeChipColors())
                }
                FilterChip(englishOnly, { englishOnly = !englishOnly; if (searched) newSearch(text, genre, englishOnly) }, { Text("English only") }, Modifier.testTag("find-english"), shape = ControlShape, colors = themeChipColors())
            }
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("find-message"), color = MaterialTheme.colorScheme.primary) }
            if (loading) Text("Searching…", Modifier.padding(horizontal = 16.dp).testTag("find-loading"))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("find-results")) {
                if (searched && shown.isEmpty() && !loading) item {
                    Text(if (entries.isEmpty()) "No books found." else "No books match this popularity. Choose Any to see all ${entries.size} found.", Modifier.padding(16.dp).testTag("find-empty"))
                }
                items(shown, key = { it.id }) { entry -> ResultRow(entry, entry.id in added) { add(entry) } }
                val page = lastPage
                if (page != null && Gutendex.hasMore(page, popularity)) item {
                    OutlinedButton({ search(Gutendex.Query(text, genre, englishOnly, pageNumber + 1)) }, Modifier.padding(16.dp).testTag("find-more"), enabled = !loading) { Text("More results") }
                }
            }
            Attribution()
        }
    }
}

@Composable
private fun ResultRow(entry: Gutendex.Entry, added: Boolean, onClick: () -> Unit) {
    val state = when {
        added -> "Added to the import queue"
        entry.epubUrl == null -> "No EPUB available"
        else -> null
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("result-${entry.id}")) {
        Text(entry.title, Modifier.testTag("result-title-${entry.id}"), style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text("${entry.author} · ${entry.languages.joinToString { it.uppercase() }} · ${"%,d".format(entry.downloads)} downloads", Modifier.testTag("result-detail-${entry.id}"), style = MaterialTheme.typography.bodyMedium)
        state?.let { Text(it, Modifier.testTag("result-state-${entry.id}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

/** Says where the books come from and what the reader must check. The wording matches docs/gutenberg.md. */
@Composable
private fun Attribution() {
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Books from Project Gutenberg, listed through the Gutendex catalogue service. This app is not made by or endorsed by Project Gutenberg. " +
                "These books are public domain in the United States; copyright can differ in other countries, so check the law where you live.",
            Modifier.testTag("find-notice"), style = MaterialTheme.typography.bodySmall,
        )
        TextButton({ links.openUri(Gutendex.SITE_URL) }, Modifier.testTag("find-gutenberg-link")) { Text("gutenberg.org") }
    }
}
