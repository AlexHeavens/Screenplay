/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var opened by mutableStateOf<String?>(null)
    private var finding by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pick the window theme before the window exists, so a chosen mode shows from the first frame.
        ThemeChoice.mode = Options.load(this).theme
        when (ThemeChoice.mode) { ThemeMode.LIGHT -> setTheme(R.style.Theme_Screenplay_Light); ThemeMode.DARK -> setTheme(R.style.Theme_Screenplay_Dark); else -> Unit }
        super.onCreate(savedInstanceState)
        Library.refresh(this)
        // Waiting books always have a worker: WorkManager keeps it across a restart and it also finishes the book that was cut off. This covers the case where it is gone.
        if (savedInstanceState == null && Library.books.value.any { it.status == Status.QUEUED }) ImportQueue.start(this)
        // A recreated activity (rotation) still holds the original intent; importing it again would duplicate the book.
        // Relaunching from history redelivers the original intent, which would import the same PDF again.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
        setContent {
            ScreenplayTheme {
                val isDark = MaterialTheme.colorScheme.background == DarkColors.background
                Surface(Modifier.fillMaxSize().testTag(if (isDark) "theme-dark" else "theme-light")) {
                    val id = opened
                    if (finding) FindBooks(onBack = { finding = false })
                    else if (id == null) Shelf(onOpen = { opened = it }, onFind = { finding = true }) else Reader(id, onBack = { opened = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) Library.import(this, uri)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shelf(onOpen: (String) -> Unit, onFind: () -> Unit) {
    val ctx = LocalContext.current
    val books by Library.books.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Library.import(ctx, uri)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val message = try {
                "Imported ${withContext(Dispatchers.IO) { Backup.restore(ctx, uri) }.joinToString { "“$it”" }}"
            } catch (e: BackupException) {
                e.message ?: "Could not import the backup."
            } catch (e: Exception) {
                "Could not import the backup: ${e.message}"
            }
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { Library.refresh(ctx); delay(1000) }
    }
    var options by remember { mutableStateOf(Options.load(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Book?>(null) }
    toDelete?.let { b ->
        DeleteBookDialog(b.title, onConfirm = { toDelete = null; MediaControl.closeIf(b.id); Library.delete(ctx, b.id) }, onDismiss = { toDelete = null })
    }
    if (showSettings) ModalBottomSheet({ showSettings = false }, Modifier.testTag("settings-panel")) {
        SettingsPanel(
            options,
            onImportBackup = { showSettings = false; backupPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onExportBackup = {
                showSettings = false
                scope.launch {
                    try { Backup.share(ctx, withContext(Dispatchers.IO) { Backup.zipLibrary(ctx) }) }
                    catch (e: Exception) { snackbar.showSnackbar("Could not export the backup: ${e.message}") }
                }
            },
        ) { options = it; it.save(ctx); ThemeChoice.mode = it.theme }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("snackbar")) },
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_open_book), contentDescription = "Open book", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Screenplay Audiobooks")
                }
            }, actions = {
                IconButton(onFind, Modifier.testTag("find-books")) {
                    Icon(Icons.Filled.Search, contentDescription = "Find books")
                }
                IconButton({ showSettings = true }, Modifier.testTag("settings")) {
                    Icon(Icons.Filled.Settings, contentDescription = "Import settings")
                }
            })
        },
        bottomBar = { MiniPlayer(onOpen) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf", "application/epub+zip")) }, modifier = Modifier.testTag("import")) {
                Text("Import PDF or EPUB")
            }
        },
    ) { pad ->
        if (books.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp)) { Text("No audiobooks yet. Import a PDF or EPUB.") }
        }
        val importing = ImportQueue.line(books)
        val downloading by QueueDownload.active.collectAsState()
        val drag = rememberQueueDrag(books) { id, to -> Library.moveTo(ctx, id, to) }
        val imported = ImportQueue.imported(books)
        LazyColumn(Modifier.padding(pad)) {
            // Shown only while a book is still being made; the queue empties and the section goes.
            if (importing.isNotEmpty()) {
                item(key = "header-importing") { SectionHeader("Importing", "section-importing") }
                items(importing, key = { it.id }) { b ->
                    BookRow(b, ImportQueue.label(books, b, downloading), onOpen, onDelete = { toDelete = b },
                        onMoveUp = if (ImportQueue.canMoveUp(books, b.id)) ({ Library.moveUp(ctx, b.id) }) else null,
                        drag = drag)
                }
            }
            if (imported.isNotEmpty()) {
                item(key = "header-imported") { SectionHeader("Imported", "section-imported") }
                items(imported, key = { it.id }) { b -> BookRow(b, b.label(), onOpen, onDelete = { toDelete = b }, onMoveUp = null, drag = null) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** One book on the shelf. A book in the import queue can be dragged ([drag]), and a waiting one behind another waiting one has [onMoveUp]; finished books have neither. */
@Composable
private fun BookRow(b: Book, label: String, onOpen: (String) -> Unit, onDelete: () -> Unit, onMoveUp: (() -> Unit)?, drag: QueueDrag?) {
    val dragging = drag?.isDragging(b.id) == true
    ListItem(
        headlineContent = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = if (b.author.isEmpty()) null else ({ Text(b.author, Modifier.testTag("author-${b.title}")) }),
        supportingContent = { Text(label, Modifier.testTag("status-${b.title}")) },
        // Three lines is the Menu icon: the drag-handle icon is in the extended icon set, which is not worth its size for one glyph.
        leadingContent = if (drag == null) null else ({
            Icon(Icons.Filled.Menu, contentDescription = "Drag to reorder ${b.title}", Modifier.testTag("drag-handle-${b.title}").then(drag.handle(b.id)).padding(8.dp))
        }),
        colors = ListItemDefaults.colors(containerColor = if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        // Selected is the state the lifted row exposes to accessibility and tests; only rows that can be dragged have it.
        modifier = Modifier.testTag("book-${b.title}").then(drag?.modifier(b.id) ?: Modifier)
            .semantics { if (drag != null) selected = dragging }
            .clickable(enabled = b.openable) { onOpen(b.id) },
        trailingContent = {
            Row {
                if (onMoveUp != null) TextButton(onClick = onMoveUp, modifier = Modifier.testTag("move-up-${b.title}").semantics { contentDescription = "Move ${b.title} up the queue" }) { Text("Move up") }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("delete-${b.title}")) { Text("Delete") }
            }
        },
    )
}

/** Asks before a book and everything made from it goes; nothing is deleted until [onConfirm]. */
@Composable
fun DeleteBookDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ControlShape,
        title = { Text("Delete audiobook?") },
        text = { Text("“$title” will be removed, with its PDF, audio, transcript and notes. This cannot be undone.") },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("confirm-delete"), shape = ControlShape, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("cancel-delete"), shape = ControlShape) { Text("Cancel") } },
    )
}

/** The book that is speaking, on the library page: pause or resume it, return to it, or stop it. */
@Composable
fun MiniPlayer(onOpen: (String) -> Unit) {
    val now = MediaControl.current ?: return
    val player = now.player
    Surface(Modifier.fillMaxWidth().testTag("mini-player"), tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).clickable { onOpen(now.id) }.testTag("mini-open")) {
                Text(now.title, Modifier.testTag("mini-title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (now.author.isNotEmpty()) Text(now.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                MediaControl.sleep.label?.let { Text(it, Modifier.testTag("mini-sleep"), style = MaterialTheme.typography.bodySmall) }
            }
            Button(onClick = player::toggle, shape = ControlShape, modifier = Modifier.testTag("mini-play")) { Text(if (player.playing) "Pause" else "Play") }
            IconButton(onClick = MediaControl::close, modifier = Modifier.testTag("mini-close")) { Icon(Icons.Filled.Close, contentDescription = "Stop and close") }
        }
    }
}

/**
 * The footnotes of paragraph [index], under it in small muted type. Read-only (unlike the reader's own paragraph note), never spoken and never
 * a place to seek to: a tap is taken here, so it does not start the paragraph's audio.
 */
@Composable
fun FootnoteAnnotation(notes: List<String>, index: Int) {
    Text(
        notes.joinToString("\n"),
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("footnote-$index"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One labelled on/off switch, with an optional line saying more under it. The whole row toggles and carries the Switch role, so semantics expose the state. */
@Composable
fun SwitchRow(label: String, checked: Boolean, tag: String, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 24.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val on = MaterialTheme.colorScheme.primary
        Switch(
            checked, null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = on, checkedTrackColor = on.copy(alpha = 0.4f), checkedBorderColor = on,
                uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.LightGray, uncheckedBorderColor = Color.Gray,
            ),
        )
    }
}

@Composable
fun SettingsPanel(options: Options, onImportBackup: () -> Unit, onExportBackup: () -> Unit, set: (Options) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Import options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        SwitchRow("Spell check", options.spell, "opt-spell") { set(options.copy(spell = it)) }
        SwitchRow("Skip front matter", options.trimFront, "opt-trim") { set(options.copy(trimFront = it)) }
        SwitchRow("Merge split sentences", options.mergeFragments, "opt-merge") { set(options.copy(mergeFragments = it)) }
        SwitchRow("Skip tables, lists & notes", options.cleanText, "opt-clean") { set(options.copy(cleanText = it)) }
        SwitchRow(
            "Footnotes as notes, not spoken", options.footnotes, "opt-footnotes",
            hint = "Scanned PDFs: kept under their paragraph and skipped by the voice. Applies to books made from now on.",
        ) { set(options.copy(footnotes = it)) }
        Text("Theme", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in ThemeMode.values()) FilterChip(options.theme == m, { set(options.copy(theme = m)) }, { Text(m.label) }, Modifier.testTag("theme-${m.name}"), shape = ControlShape, colors = themeChipColors())
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", Modifier.weight(1f))
            Box {
                OutlinedButton({ menu = true }, Modifier.testTag("opt-voice")) { Text(options.voice.label) }
                DropdownMenu(menu, { menu = false }) {
                    for (v in Voice.values()) DropdownMenuItem(
                        text = { Text(v.label) }, modifier = Modifier.testTag("voice-${v.name}"),
                        onClick = {
                            menu = false
                            val before = options
                            set(options.copy(voice = v))
                            status = null
                            if (v.neural) scope.launch {
                                try {
                                    Engines.prepareNeural(ctx, v) { status = "Downloading voice ${(it * 100).toInt()}%" }
                                    status = "Voice ready"
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    status = "Voice download failed: ${e.message ?: e.javaClass.simpleName}"
                                    set(before)
                                }
                            }
                        },
                    )
                }
            }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 24.dp).testTag("voice-status"), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onImportBackup, Modifier.testTag("import-backup")) { Text("Import backup", maxLines = 1) }
            OutlinedButton(onExportBackup, Modifier.testTag("export-backup")) { Text("Export backup", maxLines = 1) }
        }
    }
}

/** The book screen's header. A long title gives way to the buttons: it takes two lines, then an ellipsis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTopBar(
    title: String, exportLabel: String, exportEnabled: Boolean,
    onBack: () -> Unit, onExport: () -> Unit, onBackup: () -> Unit, exportMenu: @Composable () -> Unit,
) {
    TopAppBar(
        title = { Text(title, Modifier.testTag("title"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("Back") } },
        actions = {
            Box {
                TextButton(onClick = onExport, enabled = exportEnabled, modifier = Modifier.testTag("export")) { Text(exportLabel, maxLines = 1) }
                exportMenu()
            }
            TextButton(onClick = onBackup, modifier = Modifier.testTag("backup")) { Text("Back up", maxLines = 1) }
        },
    )
}

/** The sleep timer's choices, in minutes. */
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun Reader(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val book = Library.books.collectAsState().value.firstOrNull { it.id == id }
    // A book still being made grows: reload its transcript as it does, and swap to the file player once it is finished.
    val ready = book?.status == Status.READY
    var segments by remember(id, ready) { mutableStateOf(Library.segments(ctx, id)) }
    // Sentences already read but not yet spoken: shown at once, and become playable as speech reaches them.
    var queued by remember(id, ready) { mutableStateOf(if (ready) emptyList() else Library.queued(ctx, id).drop(segments.size)) }
    var notes by remember(id) { mutableStateOf(Library.notes(ctx, id)) }
    // Footnotes of a scanned book: annotations under their paragraphs, read-only and never spoken. They grow with the book while it is read.
    var footnotes by remember(id, ready) { mutableStateOf(Library.footnotes(ctx, id)) }
    // A book opens where it was left: at the start of the last paragraph played, which the player is moved to below.
    val resume = remember(id) { longArrayOf(Played.resumeAt(segments, book?.positionMs)) }
    val rate = book?.rate ?: 0
    val playable = book?.playable == true
    // Speech outlives this screen: coming back to a book that is still playing takes the running player over rather than starting a new one.
    val playerKey = Pair(ready, playable)
    val player: Playback = remember(id, playerKey) {
        MediaControl.reuse(id, playerKey)
            ?: (if (ready) Player(ctx, id) else LivePlayer(ctx, id, rate.coerceAtLeast(1))).also { if (resume[0] > 0) it.seek(resume[0]) }
    }
    // The media notification and session follow the open book; the first playback asks, once, to be allowed to show it.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(player, book?.title, book?.author) {
        MediaControl.start(ctx, MediaControl.Now(player, book?.title ?: "", book?.author ?: "", id, playerKey))
    }
    DisposableEffect(player) { onDispose { MediaControl.leave(player) } }
    LaunchedEffect(player.playing) { if (player.playing) MediaControl.askOnce(ctx) { askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
    BackHandler(onBack = onBack)
    LaunchedEffect(player) { while (true) { player.tick(); resume[0] = player.position; delay(100) } }
    if (!ready) LaunchedEffect(id) {
        while (true) {
            delay(2000)
            Library.refresh(ctx)
            // Parsed off the main thread, and a transcript that cannot be read right now keeps what is shown.
            withContext(Dispatchers.IO) { Library.readSegments(File(Library.dir(ctx, id), Files.TRANSCRIPT)) }?.let { if (it != segments) segments = it }
            Library.queued(ctx, id).drop(segments.size).let { if (it != queued) queued = it }
            withContext(Dispatchers.IO) { Library.footnotes(ctx, id) }.let { if (it != footnotes) footnotes = it }
        }
    }

    val current = Timeline.indexAt(segments, player.position)
    val importing = book?.takeIf { it.importing }?.let { ImportProgress(it.readProgress, it.speechProgress) }
    // A Boolean for the sentence rows: the progress values change on every page, and rows must not recompose with them.
    val importingNow = importing != null
    val list = rememberLazyListState()
    val exportScope = rememberCoroutineScope()
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    fun runExport(work: () -> Unit) {
        exporting = true
        exportScope.launch(Dispatchers.IO) {
            try { work() } catch (e: Exception) {
                android.util.Log.e("Export", "failed", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, "Export failed: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally { exporting = false }
        }
    }
    var scrubbing by remember { mutableStateOf<Int?>(null) }
    val contents = book?.contents.orEmpty()
    val estimates = book?.estimates.orEmpty()
    // Chapters come from the text, spoken or not, and are stored with the book. Until they are (a book still being read, or one stored
    // before chapters were kept) they are found here, off the main thread, and a finished book gets them stored on this first open.
    val stored = book?.chapters
    val texts = remember(segments, queued) { segments.map { it.text } + queued }
    val marks by produceState(stored.orEmpty(), stored, texts, contents, estimates) {
        value = stored ?: withContext(Dispatchers.Default) { Chapters.detect(texts, contents, estimates) }
    }
    if (book != null && stored == null && !book.importing) LaunchedEffect(id) { withContext(Dispatchers.IO) { Library.detectChapters(ctx, id) } }
    val chapters = remember(marks, segments) { Chapters.place(marks, segments) }
    // Per transcript change, not per playback tick; the annotations are no part of the transcript, so search, chapters and word counts never see them.
    val placedFootnotes = remember(footnotes, texts) { Footnotes.place(footnotes, texts) }
    // By item index, for the heading rows over the spoken and the read-but-unspoken text alike.
    val headings = remember(chapters) { chapters.associateBy { it.index } }
    LaunchedEffect(current) { if (segments.isNotEmpty() && scrubbing == null) list.animateScrollToItem(current) }
    var showNotes by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<String?>(null) }
    var paragraphNotes by remember(id) { mutableStateOf(ParagraphNotes.load(ctx, id)) }
    // The playback clock recomposes the reader ten times a second: place notes per transcript change, not per tick.
    val placedNotes = remember(paragraphNotes, segments) { ParagraphNotes.place(paragraphNotes, segments) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var cancelNote by remember { mutableIntStateOf(0) }
    var highlights by remember(id) { mutableStateOf(Highlights.load(ctx, id)) }
    val placedHighlights = remember(highlights, segments) { Highlights.place(highlights, segments) }
    fun setHighlight(at: Int, color: HighlightColor? = null) {
        highlights = Highlights.toggle(highlights, placedHighlights, segments, at, color)
        Highlights.save(ctx, id, highlights)
    }
    // The open colour wheel: which paragraph, where the finger went down (window pixels) and how far it has moved since.
    var wheel by remember { mutableStateOf<Triple<Int, Offset, Offset>?>(null) }
    val wheelGeometry = remember { WheelGeometry() }
    val darkTheme = isDarkTheme()
    fun hoveredSlice(w: Triple<Int, Offset, Offset>, density: Float): Int? {
        val d = w.second + w.third - wheelGeometry.centre
        return HighlightWheel.sliceAt(d.x / density, d.y / density)
    }
    BackHandler(enabled = showNotes) { showNotes = false }

    // The long-press menu of a paragraph, and the edit and delete-confirmation dialogs it opens. Only a finished book can be changed:
    // while one is being made the speech worker is still writing its transcript.
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var editParagraph by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    fun applyEdit(change: (TranscriptState) -> TranscriptState) {
        Library.editTranscript(ctx, id, change)?.let {
            segments = it.segments
            paragraphNotes = it.paragraphNotes
            highlights = it.highlights
        }
    }

    // Every note in the book, timestamped and paragraph alike, in reading order with its place in the book.
    val noteItems = remember(notes, placedNotes, placedHighlights, segments, chapters) {
        val total = (segments.lastOrNull()?.end ?: 0L).coerceAtLeast(1L)
        fun item(key: String, at: Long, text: String, paragraph: Int?) = NoteItem(
            key, at, (at * 100 / total).toInt().coerceIn(0, 100),
            Chapters.at(chapters, Timeline.indexAt(segments, at))?.title ?: "Start", text, paragraph,
        )
        // A paragraph with a highlight, a note or both is one row; a highlighted one also quotes its text in its pen colour.
        val paragraphs = (placedNotes.keys + placedHighlights.keys).sorted().map { i ->
            val h = placedHighlights[i]?.color
            item("paragraph-$i", segments[i].start, placedNotes[i]?.text.orEmpty(), i).copy(quote = h?.let { segments[i].text }, color = h)
        }
        (notes.map { item(it.id, it.at, it.text, null) } + paragraphs).sortedBy { it.at }
    }
    // A blank text removes the note.
    fun setNote(n: NoteItem, text: String) {
        if (n.paragraph == null) {
            notes = if (text.isEmpty()) notes.filter { it.id != n.key } else notes.map { if (it.id == n.key) it.copy(text = text) else it }
            Library.saveNotes(ctx, id, notes)
        } else {
            val others = paragraphNotes - setOfNotNull(placedNotes[n.paragraph])
            paragraphNotes = if (text.isEmpty()) others else others + ParagraphNote(n.paragraph, ParagraphNotes.hash(segments[n.paragraph].text), text)
            ParagraphNotes.save(ctx, id, paragraphNotes)
        }
    }

    Scaffold(
        topBar = {
            BookTopBar(
                title = book?.title ?: "",
                exportLabel = if (exporting) "Exporting…" else "Export",
                // The audio is one file only once the book is ready, so an export waits until then.
                exportEnabled = ready && !exporting,
                onBack = onBack,
                onExport = { exportMenu = true },
                onBackup = { Backup.share(ctx, Backup.zip(ctx, id, book?.title ?: id)) },
            ) {
                DropdownMenu(exportMenu, { exportMenu = false }) {
                    DropdownMenuItem(text = { Text("Audiobook (one M4B)") }, modifier = Modifier.testTag("export-m4b"), onClick = {
                        exportMenu = false
                        runExport { Export.share(ctx, Export.m4b(ctx, id), "Export audiobook") }
                    })
                    DropdownMenuItem(text = { Text("Export as EPUB") }, modifier = Modifier.testTag("export-epub"), onClick = {
                        exportMenu = false
                        runExport { Export.share(ctx, Export.epub(ctx, id), "Export EPUB") }
                    })
                    DropdownMenuItem(text = { Text("One M4A per chapter") }, modifier = Modifier.testTag("export-chapters"), onClick = {
                        exportMenu = false
                        runExport { Export.share(ctx, Export.chapterFiles(ctx, id), "Export chapters") }
                    })
                }
            }
        },
        bottomBar = {
            Column(Modifier.padding(8.dp)) {
                if (book?.importing == true) {
                    val read = book.readProgress
                    val speech = book.speechProgress
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Reading ${(read * 100).toInt()}%", Modifier.testTag("progress-read").semantics { progressBarRangeInfo = ProgressBarRangeInfo(read, 0f..1f) },
                            style = MaterialTheme.typography.labelMedium, color = ReadLabelGrey)
                        Text("Speaking ${(speech * 100).toInt()}%", Modifier.testTag("progress-speech").semantics { progressBarRangeInfo = ProgressBarRangeInfo(speech, 0f..1f) },
                            style = MaterialTheme.typography.labelMedium, color = SpeechGrey)
                    }
                }
                Slider(
                    value = player.position.toFloat(),
                    onValueChange = { player.seek(it.toLong()) },
                    valueRange = 0f..player.duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.testTag("seek"),
                )
                // The buttons and the progress under them share one centre line. Each row is only as wide as its content,
                // and wraps onto a second line, still centred, on a narrow screen instead of squeezing or clipping a button.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.testTag("controls"), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Button(onClick = player::toggle, enabled = playable, shape = ControlShape, modifier = Modifier.testTag("play")) { Text(if (player.playing) "Pause" else "Play") }
                    OutlinedButton(onClick = player::cycleSpeed, shape = ControlShape, modifier = Modifier.testTag("speed")) { Text("${player.speed}×") }
                    // Opens the Notes panel; while the panel is open the button is its close.
                    OutlinedButton(onClick = { showNotes = !showNotes }, shape = ControlShape, modifier = Modifier.testTag("notes")) {
                        if (showNotes) Icon(Icons.Filled.Close, contentDescription = "Close notes") else Text("Notes")
                    }
                    // Jumps to a chapter heading: scrolls the text there, and moves playback there once it has been spoken.
                    Box {
                        var chapterMenu by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { chapterMenu = true }, enabled = chapters.isNotEmpty(), shape = ControlShape, modifier = Modifier.testTag("chapters")) { Text("Chapters") }
                        DropdownMenu(chapterMenu, { chapterMenu = false }) {
                            for (c in chapters) DropdownMenuItem(text = { Text(c.title) }, modifier = Modifier.testTag("chapter-item-${c.index}"), onClick = {
                                chapterMenu = false
                                exportScope.launch { list.animateScrollToItem(c.index) }
                                if (c.index < segments.size && playable) player.seek(c.start)
                            })
                        }
                    }
                    Box {
                        var sleepMenu by remember { mutableStateOf(false) }
                        val sleep = MediaControl.sleep
                        OutlinedButton(onClick = { sleepMenu = true }, shape = ControlShape, modifier = Modifier.testTag("sleep")) { Text(sleep.label ?: "Sleep") }
                        DropdownMenu(sleepMenu, { sleepMenu = false }) {
                            DropdownMenuItem(text = { Text("Off") }, modifier = Modifier.testTag("sleep-off"), onClick = { sleepMenu = false; sleep.cancel() })
                            for (minutes in SLEEP_MINUTES) DropdownMenuItem(text = { Text("$minutes minutes") }, modifier = Modifier.testTag("sleep-$minutes"), onClick = { sleepMenu = false; sleep.startMinutes(minutes) })
                            // The next chapter's start, or the end of a finished book; a book still being made has no end to stop at yet.
                            val chapterEnd = chapters.firstOrNull { it.start > player.position }?.start ?: player.duration.takeIf { ready }
                            if (chapters.isNotEmpty() && chapterEnd != null) {
                                DropdownMenuItem(text = { Text("End of chapter") }, modifier = Modifier.testTag("sleep-chapter"), onClick = { sleepMenu = false; sleep.startAtPosition(chapterEnd) })
                            }
                        }
                    }
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.testTag("progress-line"), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Text("${clock(player.position)} / ${clock(player.duration)}", Modifier.testTag("clock").align(Alignment.CenterVertically))
                    // The live position, over the whole book, or an estimate of it while the book is still being made.
                    if (book != null && (player.position > 0 || book.positionMs != null)) {
                        val percent = Played.percent(player.position, false, player.duration, ready, book.speechProgress)
                        Text("$percent% played", Modifier.testTag("played").align(Alignment.CenterVertically))
                    }
                }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
        LazyColumn(Modifier.padding(horizontal = 32.dp), state = list) {
            itemsIndexed(segments) { i, s ->
                // A heading the text already has is spoken as its own sentence and drawn as a heading there; a title only the contents had gets a row, not spoken.
                val chapter = headings[i]
                val own = chapter != null && Chapters.isTitleOf(chapter, s.text)
                if (chapter != null && !own) ChapterTitle(chapter.title, i)
                val note = notes.filter { it.at in s.start until s.end || (i == segments.lastIndex && it.at >= s.end) }
                val painted = placedHighlights[i]?.color
                val density = LocalDensity.current.density
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val bands = remember(textLayout, density, painted != null) {
                    if (painted == null) emptyList() else HighlightBands.forLayout(textLayout, density)
                }
                SwipeRow(
                    onRight = { if (editing == i) cancelNote++ else editing = i }, rightHint = if (editing == i) "Cancel" else "✎ Note", // a swipe right on an open note field drops it
                    onLeft = { setHighlight(i) }, leftHint = "Highlight",
                    leftHold = HoldPicker(
                        onOpen = { wheel = Triple(i, it, Offset.Zero) },
                        onMove = { d -> wheel = wheel?.copy(third = d) },
                        onRelease = { d ->
                            wheel?.let { w -> hoveredSlice(w.copy(third = d), density)?.let { setHighlight(i, HighlightColor.entries[it]) } }
                            wheel = null
                        },
                    ),
                ) {
                Column(
                    Modifier.fillMaxWidth().combinedClickable(
                        onLongClick = { if (ready) menuFor = i },
                        onClick = { if (i == current) player.toggle() else { player.seek(s.start); if (!player.playing) player.toggle() } }, // the speaking paragraph pauses and resumes; another one speaks from its start
                    )
                        .padding(vertical = 6.dp)
                        .testTag("sentence-$i").semantics { selected = i == current; painted?.let { highlight = it.name; highlightBands = bands } },
                ) {
                    Text(s.text, (if (own) Modifier.chapterHeading(i) else Modifier).then(if (painted != null) Modifier.highlighterBands(bands, painted.pen(darkTheme)) else Modifier),
                        onTextLayout = { textLayout = it },
                        style = if (own) MaterialTheme.typography.headlineSmall else if (i == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (own || (painted != null && i == current)) FontWeight.Bold else null,
                        color = when {
                            painted != null -> MaterialTheme.colorScheme.onSurface // the palette is checked against this colour; the speaking paragraph is bold and larger instead of blue
                            i == current -> MaterialTheme.colorScheme.primary
                            importingNow -> SpokenTextGrey // while importing, spoken sentences are the dark ones and the unspoken text below them is light
                            else -> MaterialTheme.colorScheme.onSurface
                        })
                    Box {
                        DropdownMenu(menuFor == i, { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Edit") }, modifier = Modifier.testTag("paragraph-menu-edit"), onClick = { menuFor = null; editParagraph = i })
                            DropdownMenuItem(text = { Text("Delete") }, modifier = Modifier.testTag("paragraph-menu-delete"), onClick = {
                                menuFor = null
                                if (placedNotes[i] != null || placedHighlights[i] != null) confirmDelete = i else applyEdit { ParagraphEdit.delete(it, i) }
                            })
                        }
                    }
                    placedFootnotes[i]?.let { FootnoteAnnotation(it, i) }
                    val existing = placedNotes[i]
                    if (editing == i) {
                        InlineNoteField(existing?.text.orEmpty(), cancelTick = cancelNote, onCancel = { editing = null }) { t ->
                            setNote(NoteItem("paragraph-$i", s.start, 0, "", "", i), t)
                            editing = null
                        }
                    } else if (existing != null) {
                        Row(Modifier.clickable { editing = i }.testTag("paragraph-note-$i")) {
                            Text("✎ ", Modifier.testTag("paragraph-note-marker-$i"), style = MaterialTheme.typography.bodySmall)
                            Text(existing.text, Modifier.testTag("paragraph-note-text-$i"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    note.forEach { n ->
                        Row {
                            Text("📝 ${clock(n.at)} ${n.text}", Modifier.testTag("note-${n.text}").weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                notes = notes - n; Library.saveNotes(ctx, id, notes)
                            }, modifier = Modifier.testTag("delete-note-${n.text}")) { Text("Delete") }
                        }
                    }
                }
                }
            }
            itemsIndexed(queued) { i, text ->
                val chapter = headings[segments.size + i]
                val own = chapter != null && Chapters.isTitleOf(chapter, text)
                if (chapter != null && !own) ChapterTitle(chapter.title, segments.size + i)
                Text(text, Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("queued-${segments.size + i}"),
                    style = if (own) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (own) FontWeight.Bold else null, color = ReadLabelGrey)
                placedFootnotes[segments.size + i]?.let { Box(Modifier.fillMaxWidth()) { FootnoteAnnotation(it, segments.size + i) } }
            }
        }
        if (segments.isNotEmpty() || importing != null) {
            BookScrubber(segments.size + queued.size, chapters, list, { scrubbing = it }, Modifier.align(Alignment.CenterStart).testTag("scrub-left"), importing)
            BookScrubber(segments.size + queued.size, chapters, list, { scrubbing = it }, Modifier.align(Alignment.CenterEnd).testTag("scrub-right"), importing)
        }
        scrubbing?.let { i ->
            Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                Text("${Chapters.at(chapters, i)?.title ?: "Start"} · ${segments.getOrNull(i)?.let { clock(it.start) } ?: "not yet spoken"}",
                    Modifier.padding(16.dp).testTag("scrub-label"))
            }
        }
        editParagraph?.let { at ->
            segments.getOrNull(at)?.let { paragraph ->
                ParagraphEditDialog(paragraph.text, onDismiss = { editParagraph = null }) { text ->
                    editParagraph = null
                    applyEdit { ParagraphEdit.replace(it, at, text) }
                }
            }
        }
        confirmDelete?.let { at ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete this paragraph?") },
                text = { Text("Its note and highlight go with it. This cannot be undone.") },
                confirmButton = { TextButton({ confirmDelete = null; applyEdit { ParagraphEdit.delete(it, at) } }, Modifier.testTag("paragraph-delete-confirm")) { Text("Delete") } },
                dismissButton = { TextButton({ confirmDelete = null }, Modifier.testTag("paragraph-delete-cancel")) { Text("Cancel") } },
            )
        }
        wheel?.let { w -> HighlightWheelPopup(w.second, wheelGeometry, hoveredSlice(w, LocalDensity.current.density), darkTheme) }
        AnimatedVisibility(showNotes, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.8f),
            enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            NotesPanel(
                noteItems, editingNote,
                onOpen = { player.seek(it.at); if (!player.playing) player.toggle(); showNotes = false },
                onEdit = { editingNote = it.key },
                onEdited = { n, t -> setNote(n, t); editingNote = null },
                onDelete = { n -> if (n.text.isEmpty() && n.paragraph != null) setHighlight(n.paragraph) else setNote(n, "") },
                onCancelEdit = { editingNote = null },
                onClose = { showNotes = false },
            )
        }
        }
    }
}

private fun Modifier.chapterHeading(index: Int) = testTag("chapter-$index").semantics { heading() }

/** A chapter title above the first paragraph of its chapter, in a row of its own and not spoken. */
@Composable
private fun ChapterTitle(title: String, index: Int) =
    Text(title, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).chapterHeading(index),
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

/** Edits one paragraph's text. A new line splits it into more paragraphs, so the hint says so; blank text cannot be saved (delete the paragraph instead). */
@Composable
private fun ParagraphEditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit paragraph") },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("paragraph-edit-input"),
                supportingText = { Text("A new line splits it into two paragraphs.") })
        },
        confirmButton = { TextButton({ onSave(text) }, Modifier.testTag("paragraph-edit-save"), enabled = ParagraphEdit.parts(text).isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("paragraph-edit-cancel")) { Text("Cancel") } },
    )
}
