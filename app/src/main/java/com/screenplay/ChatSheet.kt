/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chat with the model about the book. [promptFor] makes the text sent for a question and the turns so far (it runs off the main thread).
 * While [blocked] (an import is using the model) the reader is told to wait and cannot ask.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(title: String, blocked: Boolean, promptFor: (String, List<ChatTurn>) -> String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val chat = remember { Engines.chat(ctx) }
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<ChatTurn>() }
    var question by remember { mutableStateOf("") }
    // The answer being written, and the question it answers; null when no question is being answered.
    var writing by remember { mutableStateOf<ChatTurn?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var answering by remember { mutableStateOf<Job?>(null) }
    // Leaving the chat stops an answer under way (the model is told to give up), then gives the model back.
    DisposableEffect(Unit) { onDispose { answering?.cancel(); chat.close() } }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, writing?.answer) { list.scrollToItem((turns.size + (if (writing != null) 1 else 0) - 1).coerceAtLeast(0)) }

    val ask = {
        val asked = question.trim()
        question = ""
        failure = null
        writing = ChatTurn(asked, "")
        answering = scope.launch {
            try {
                val earlier = turns.toList()
                val prompt = withContext(Dispatchers.Default) { promptFor(asked, earlier) }
                val answer = chat.answer(prompt) { piece -> writing = ChatTurn(asked, piece) }
                turns += ChatTurn(asked, answer.trim())
            } catch (e: CancellationException) {
                // Stopped by the reader: what was written so far is kept, marked as stopped.
                writing?.takeIf { it.answer.isNotBlank() }?.let { turns += it.copy(answer = it.answer.trim() + " …") }
                throw e
            } catch (e: Exception) {
                failure = (e as? SummaryFailure)?.message ?: "the model could not answer"
            } finally {
                writing = null
                answering = null
            }
        }
    }

    ModalBottomSheet(onClose, Modifier.testTag("chat-panel")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ask about $title", style = MaterialTheme.typography.titleMedium)
            val shown = turns.toList() + listOfNotNull(writing)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(shown) { i, turn ->
                    Column(Modifier.testTag("chat-turn-$i")) {
                        Text(turn.question, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("chat-question-$i"))
                        Text(turn.answer.ifBlank { "Thinking…" }, modifier = Modifier.testTag("chat-answer-$i"))
                    }
                }
            }
            if (blocked) Text("The model is busy with an import. Ask again once it has finished.", modifier = Modifier.testTag("chat-blocked"))
            failure?.let { Text("Could not answer: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chat-failure")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    question, { question = it }, Modifier.weight(1f).testTag("chat-input"),
                    enabled = !blocked && answering == null, placeholder = { Text("Ask a question") },
                )
                if (answering != null) Button(onClick = { answering?.cancel() }, modifier = Modifier.testTag("chat-stop")) { Text("Stop") }
                else Button(onClick = { ask() }, enabled = !blocked && question.isNotBlank(), modifier = Modifier.testTag("chat-send")) { Text("Send") }
            }
        }
    }
}
