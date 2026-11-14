/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * A note on one paragraph (transcript segment). It is keyed by the paragraph's position and a hash of its text,
 * so it stays on the right paragraph when the transcript grows or shifts while pages are still being read.
 */
data class ParagraphNote(val index: Int, val hash: Int, val text: String)

object ParagraphNotes {
    fun hash(paragraph: String) = paragraph.hashCode()

    fun load(ctx: Context, id: String): List<ParagraphNote> {
        val f = File(Library.dir(ctx, id), Files.PARAGRAPH_NOTES)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return List(a.length()) { val o = a.getJSONObject(it); ParagraphNote(o.getInt("index"), o.getInt("hash"), o.getString("text")) }
    }

    fun save(ctx: Context, id: String, notes: List<ParagraphNote>) {
        val a = JSONArray()
        notes.forEach { a.put(JSONObject().put("index", it.index).put("hash", it.hash).put("text", it.text)) }
        Library.writeAtomically(File(Library.dir(ctx, id), Files.PARAGRAPH_NOTES), a.toString())
    }

    /** Each note's paragraph: the one with the same text hash nearest to the note's old position. A note whose text is gone is not shown. */
    fun place(notes: List<ParagraphNote>, segments: List<Segment>): Map<Int, ParagraphNote> = placeBy(notes, segments, { it.hash }, { it.index })

    /** The matching [place] uses, shared with paragraph highlights, which are keyed the same way. */
    fun <T> placeBy(items: List<T>, segments: List<Segment>, hashOf: (T) -> Int, indexOf: (T) -> Int): Map<Int, T> {
        val hashes = segments.map { hash(it.text) }
        val placed = HashMap<Int, T>()
        for (n in items) {
            val at = hashes.indices.filter { hashes[it] == hashOf(n) && it !in placed }.minByOrNull { kotlin.math.abs(it - indexOf(n)) } ?: continue
            placed[at] = n
        }
        return placed
    }
}

/** A swipe this far (dp) either way triggers the row's action. */
private const val SWIPE_DP = 96

/** How long a left swipe must be held past the threshold before it opens a picker rather than acting. */
const val HOLD_MS = 400L

/**
 * A left swipe that is held opens a picker instead of firing [SwipeRow]'s `onLeft`. [onOpen] gets the finger's place in window pixels;
 * [onMove] and [onRelease] get the finger's offset from there, until it lifts.
 */
class HoldPicker(val onOpen: (Offset) -> Unit, val onMove: (Offset) -> Unit, val onRelease: (Offset) -> Unit)

/**
 * Lets the user drag [content] sideways: past a threshold to the right [onRight] fires once, and to the left [onLeft] does (when given).
 * With a [leftHold], the left action waits for the finger to lift, unless the finger stays down past [HOLD_MS], which opens the picker.
 * The row follows the finger and a hint shows behind it. Only horizontal movement is claimed, so scrolling and taps are untouched.
 */
@Composable
fun SwipeRow(
    onRight: () -> Unit,
    rightHint: String,
    modifier: Modifier = Modifier,
    onLeft: (() -> Unit)? = null,
    leftHint: String = "Delete",
    leftHold: HoldPicker? = null,
    content: @Composable () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { SWIPE_DP.dp.toPx() }
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    val hold by rememberUpdatedState(leftHold)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        if (drag > 0f) {
            Text(rightHint, Modifier.align(Alignment.CenterStart).alpha((drag / threshold).coerceAtMost(1f)).testTag("swipe-hint"),
                style = MaterialTheme.typography.labelMedium)
        } else if (drag < 0f) {
            Text(leftHint, Modifier.align(Alignment.CenterEnd).alpha((-drag / threshold).coerceAtMost(1f)).testTag("swipe-hint-left"),
                style = MaterialTheme.typography.labelMedium)
        }
        Box(
            Modifier.fillMaxWidth().offset { IntOffset(drag.roundToInt(), 0) }.pointerInput(threshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    var armedAt: Long? = null // when the finger passed the left threshold, while a picker could still open
                    var now = down.uptimeMillis
                    var at = down.position
                    fun move(amount: Float) {
                        drag = (drag + amount).coerceIn(if (left != null) -threshold * 1.5f else 0f, threshold * 1.5f)
                        if (!fired && drag >= threshold) { fired = true; right() }
                        else if (!fired && drag <= -threshold) {
                            if (hold != null) { if (armedAt == null) armedAt = now } else { fired = true; left?.invoke() }
                        } else if (drag > -threshold) armedAt = null
                    }
                    val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); move(over) }
                    if (first == null) { drag = 0f; return@awaitEachGesture }
                    at = first.position; now = first.uptimeMillis
                    var picking = false
                    var delta = Offset.Zero
                    while (true) {
                        val wait = armedAt?.takeIf { !picking }?.let { (HOLD_MS - (now - it)).coerceAtLeast(0) }
                        val event = if (wait != null) withTimeoutOrNull(wait) { awaitPointerEvent() } else awaitPointerEvent()
                        if (event == null) {
                            // Held: the picker opens under the finger and the row springs back.
                            picking = true
                            hold?.onOpen?.invoke(origin + at + Offset(drag, 0f))
                            drag = 0f
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        now = change.uptimeMillis
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            if (picking) hold?.onRelease?.invoke(delta) else if (armedAt != null) left?.invoke()
                            break
                        }
                        if (change.isConsumed) break
                        val step = change.positionChange()
                        at = change.position
                        if (picking) { delta += step; hold?.onMove?.invoke(delta) } else move(step.x)
                        change.consume()
                    }
                    drag = 0f
                }
            },
        ) { content() }
    }
}

/** Notes are prose: start each sentence with a capital instead of the keyboard's lowercase default. */
val NoteKeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)

/**
 * The note's text field, in the transcript under its paragraph. Saves on Done or when focus leaves; blank text removes the note.
 * When the owner raises [cancelTick] (a swipe right on the field or its row) the draft is dropped: [onCancel] runs and nothing is saved.
 */
@Composable
fun InlineNoteField(initial: String, tag: String = "paragraph-note-input", cancelTick: Int = 0, onCancel: () -> Unit = {}, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun finish() { if (!done) { done = true; onCommit(text.trim()) } }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    val openedAt = remember { cancelTick }
    LaunchedEffect(cancelTick) { if (cancelTick != openedAt && !done) { done = true; onCancel() } }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    OutlinedTextField(
        text, { text = it },
        Modifier.fillMaxWidth().focusRequester(focus).testTag(tag)
            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() },
        textStyle = MaterialTheme.typography.bodySmall,
        keyboardOptions = NoteKeyboardOptions,
        keyboardActions = KeyboardActions(onDone = { finish() }),
    )
}
