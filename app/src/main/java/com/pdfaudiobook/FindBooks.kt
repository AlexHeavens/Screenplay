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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Find books": searches the stored book list of Project Gutenberg ([Catalogue]) as the reader types, and adds a tapped book to the import
 * queue as an EPUB. The list is downloaded the first time the screen opens and refreshed at most once a day; searching never uses the network.
 * A tap sends no request itself, the queue fetches the file ([QueueDownload]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindBooks(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by Library.books.collectAsState()
    val downloading by QueueDownload.active.collectAsState()
    val state by Catalogue.state.collectAsState()
    var text by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<Genre?>(null) }
    var popularity by remember { mutableStateOf(Popularity.ANY) }
    var englishOnly by remember { mutableStateOf(true) }
    var shownCount by remember(text, genre, popularity, englishOnly) { mutableStateOf(PAGE_SIZE) }
    var message by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<Int>()) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { Catalogue.open(ctx) }

    val data = (state as? Catalogue.State.Ready)?.data
    val query = CatalogueQuery(text, genre, popularity, englishOnly)
    val result by produceState<CatalogueResult?>(null, data, query, shownCount) { value = data?.let { withContext(Dispatchers.Default) { it.search(query, shownCount) } } }

    fun add(entry: CatalogueEntry) {
        if (entry.id in added) return
        // The book joins the queue at once; the queue fetches its file ([QueueDownload]), so taps never make parallel requests.
        added = added + entry.id
        Library.enqueueDownload(ctx, entry.title, "gutenberg:${entry.id}", entry.epubUrl)
        message = "Added “${entry.title}” to the import queue."
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Find books") },
            navigationIcon = { IconButton(onBack, Modifier.testTag("find-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("find-query"), singleLine = true, label = { Text("Title or author") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Text("Genre", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Genre.values().toList()) { g ->
                    FilterChip(genre == g, { genre = if (genre == g) null else g }, { Text(g.label) }, Modifier.testTag("genre-${g.name}"), shape = ControlShape, colors = themeChipColors())
                }
            }
            Text("Popularity (downloads)", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Popularity.values()) {
                    FilterChip(popularity == p, { popularity = p }, { Text(if (p.minDownloads == 0) p.label else "${p.label} (${"%,d".format(p.minDownloads)}+)") }, Modifier.testTag("popularity-${p.name}"), shape = ControlShape, colors = themeChipColors())
                }
                FilterChip(englishOnly, { englishOnly = !englishOnly }, { Text("English only") }, Modifier.testTag("find-english"), shape = ControlShape, colors = themeChipColors())
            }
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("find-message"), color = MaterialTheme.colorScheme.primary) }
            when (val s = state) {
                is Catalogue.State.Opening -> Text("Opening the book list…", Modifier.padding(16.dp).testTag("find-loading"))
                is Catalogue.State.Downloading -> Text("Downloading the book list. ${s.progress.label.removePrefix("Downloading").trim()}".trim(), Modifier.padding(16.dp).testTag("find-loading"))
                is Catalogue.State.Failed -> Column(Modifier.padding(16.dp)) {
                    Text(s.message, Modifier.testTag("find-failed"), color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ scope.launch { Catalogue.open(ctx) } }, Modifier.testTag("find-retry")) { Text("Try again") }
                }
                is Catalogue.State.Ready -> Unit
            }
            val found = result
            if (found != null) Text("${"%,d".format(found.total)} books", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("find-count"), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("find-results")) {
                if (found != null && found.total == 0) item {
                    Text(if (popularity == Popularity.ANY) "No books found." else "No books found. Choose Any popularity to see more.", Modifier.padding(16.dp).testTag("find-empty"))
                }
                items(found?.entries.orEmpty(), key = { it.id }) { entry -> ResultRow(entry, entry.id in added, books.firstOrNull { it.source == "gutenberg:${entry.id}" }?.let { downloading[it.id] }) { add(entry) } }
                if (found != null && found.total > found.entries.size) item {
                    OutlinedButton({ shownCount += PAGE_SIZE }, Modifier.padding(16.dp).testTag("find-more")) { Text("More results") }
                }
            }
            Attribution()
        }
    }
}

private const val PAGE_SIZE = 50

@Composable
private fun ResultRow(entry: CatalogueEntry, added: Boolean, download: DownloadProgress?, onClick: () -> Unit) {
    val state = when {
        download != null -> download.label
        added -> "Added to the import queue"
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
            "Books from Project Gutenberg. The list comes from Project Gutenberg's public catalogue data, copied daily to this app's GitHub page, and is searched on this device. " +
                "This app is not made by or endorsed by Project Gutenberg. " +
                "These books are public domain in the United States; copyright can differ in other countries, so check the law where you live.",
            Modifier.testTag("find-notice"), style = MaterialTheme.typography.bodySmall,
        )
        TextButton({ links.openUri(Gutenberg.SITE_URL) }, Modifier.testTag("find-gutenberg-link")) { Text("gutenberg.org") }
    }
}
