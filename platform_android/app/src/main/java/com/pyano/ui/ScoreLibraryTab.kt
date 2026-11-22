// Concern: presents the private offline score library | Non-concern: PDF rendering | IO: (library state and actions) -> score controls
package com.pyano.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pyano.scores.Score
import com.pyano.scores.ScoreLibraryController
import com.pyano.scores.ScoreLibraryState

@Composable
fun ScoreLibraryTab(controller: ScoreLibraryController, onOpen: (Score) -> Unit = {}, onServerSettings: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    DisposableEffect(controller) {
        controller.reload()
        onDispose { controller.cancel() }
    }
    ScoreLibraryContent(state, controller::refresh, controller::remove, controller::cancel,
        { score -> controller.downloadAndOpen(score) { onOpen(score) } }, onServerSettings)
}

@Composable
fun ScoreLibraryContent(
    state: ScoreLibraryState,
    onRefresh: () -> Unit,
    onRemove: (Score) -> Unit,
    onCancel: () -> Unit,
    onOpen: (Score) -> Unit = {},
    onServerSettings: () -> Unit = {},
) {
    var cancelRequested by remember(state.busy) { mutableStateOf(false) }
    var pendingRemoval by remember(state.namespace) { mutableStateOf<Score?>(null) }
    val removal = pendingRemoval?.takeIf { pending -> state.rows.any { it.score == pending && it.localCopy } }
    if (removal != null) AlertDialog(
        onDismissRequest = { pendingRemoval = null },
        title = { Text("Remove local copy?") },
        text = { Text("Remove the downloaded copy of “${removal.title}” from this tablet? Cloud is not changed. If the PDF was removed from Cloud, this may be its only remaining copy.") },
        confirmButton = {
            TextButton(onClick = { pendingRemoval = null; onRemove(removal) }, enabled = !state.busy) { Text("Remove local copy") }
        },
        dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
    )
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Private scores", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (state.busy) { cancelRequested = true; onCancel() } else onRefresh()
            }, enabled = state.namespace != null && !cancelRequested,
                modifier = Modifier.semantics {
                    contentDescription = if (cancelRequested) "Canceling operation" else if (state.busy) "Cancel operation" else "Refresh scores"
                }) {
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = null)
            }
            IconButton(onClick = { onCancel(); onServerSettings() }) {
                Icon(Icons.Default.Settings, contentDescription = "Server settings")
            }
        }
        if (!state.busy && state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.namespace != null && state.rows.isEmpty() && !state.busy) Text("No scores yet. Refresh while connected to Cloud.")
        LazyColumn {
            items(state.rows, key = { it.score.identity }) { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val alternatives = state.rows.filter { it.score.scoreId == row.score.scoreId }
                    val savedCopies = alternatives.filter { !it.canDownload }
                    val title = if (alternatives.size > 1 && !row.canDownload) {
                        val number = if (savedCopies.size > 1) " ${savedCopies.indexOf(row) + 1}" else ""
                        "${row.score.title} (saved copy$number)"
                    } else row.score.title
                    Column(Modifier.weight(1f)
                        .clickable(enabled = !state.busy && (row.downloaded || row.canDownload), onClickLabel = "Open PDF") { onOpen(row.score) }
                        .padding(vertical = 16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (row.localCopy && !row.downloaded) Text(
                            if (row.canDownload) "Local copy unavailable. Tap the title to download again."
                            else "Local copy unavailable and no longer in Cloud. Remove this copy or restore the score in Cloud.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (row.localCopy) IconButton(onClick = { pendingRemoval = row.score }, enabled = !state.busy) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove local copy of ${row.score.title}")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
