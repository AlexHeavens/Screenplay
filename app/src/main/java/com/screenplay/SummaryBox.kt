/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A chapter's overview and key lessons, in the reading view just before its first sentence. Not part of the book's own audio;
 * when the chapter has a spoken clip, [onListen] plays or stops it.
 */
@Composable
fun SummaryBox(index: Int, summary: ChapterSummary, listening: Boolean, onListen: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            // Merged so the text reads as one block; the button stays outside it, a control of its own.
            Column(Modifier.weight(1f).testTag("summary-$index").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Key conclusions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                if (summary.overview.isNotEmpty()) Text(summary.overview, style = MaterialTheme.typography.bodyMedium)
                summary.bullets.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (summary.audio) TextButton(onListen, Modifier.testTag("summary-listen-$index")) { Text(if (listening) "Stop" else "Listen") }
        }
    }
}

/** Why the summaries could not be made, with a retry for the chapters still without one. */
@Composable
fun SummaryStatus(why: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("summary-status"), verticalAlignment = Alignment.CenterVertically) {
        Text("Chapter summaries unavailable: $why", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onRetry, Modifier.testTag("summary-retry")) { Text("Retry") }
    }
}
