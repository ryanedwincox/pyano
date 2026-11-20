// Concern: presents the private offline score library | Non-concern: PDF rendering | IO: (library state and actions) -> score controls
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyano.scores.Score
import com.pyano.scores.ScoreLibraryController
import com.pyano.scores.ScoreLibraryState

@Composable
fun ScoreLibraryTab(controller: ScoreLibraryController) {
    val state by controller.state.collectAsState()
    DisposableEffect(controller) {
        controller.reload()
        onDispose { controller.cancel() }
    }
    ScoreLibraryContent(state, controller::refresh, controller::download, controller::remove, controller::cancel)
}

@Composable
fun ScoreLibraryContent(
    state: ScoreLibraryState,
    onRefresh: () -> Unit,
    onDownload: (Score) -> Unit,
    onRemove: (Score) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Private scores", style = MaterialTheme.typography.titleLarge)
        if (state.namespace != null) {
            Text("${state.endpoint}\nOwner: ${state.ownerId}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = !state.busy) { Text("Refresh scores") }
                if (state.busy) OutlinedButton(onClick = onCancel) { Text("Cancel operation") }
            }
        }
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.namespace != null && state.rows.isEmpty() && !state.busy) Text("No scores yet. Refresh while connected to Cloud.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.rows, key = { it.score.identity }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.score.title, style = MaterialTheme.typography.titleMedium)
                        Text(row.status, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!row.downloaded && row.canDownload) Button(onClick = { onDownload(row.score) }, enabled = !state.busy) {
                                Text("Download PDF")
                            }
                            if (row.localCopy) OutlinedButton(onClick = { onRemove(row.score) }, enabled = !state.busy) { Text("Remove local copy") }
                        }
                    }
                }
            }
        }
    }
}
