// Concern: shows recording save decisions and private sync state | Non-concern: publishing | IO: (take and cloud state) -> controls
package com.pyano.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.pyano.audio.RecordingNames
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.pyano.sync.RecordingCloudState

@Composable
fun SaveTakeDialog(busy: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, error: String? = null) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Save recording?") },
        text = { Text(error ?: "Save this take and upload it privately to Cloud, or discard it.") },
        confirmButton = { TextButton(enabled = !busy, onClick = onSave) { Text(if (busy) "Working…" else "Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDiscard) { Text("Discard") } }
    )
}

@Composable
fun SaveTakeFlow(
    busy: Boolean, suggestedName: String, onSave: (String) -> Unit, onDiscard: () -> Unit,
    error: String? = null, retryNameLocked: Boolean = true,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    if (!naming) SaveTakeDialog(busy, { naming = true }, onDiscard, error)
    else AlertDialog(
        onDismissRequest = { if (!busy) naming = false },
        title = { Text("Name recording") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy && (!accepted || !retryNameLocked),
                    label = { Text("Recording name") }, singleLine = true, isError = !RecordingNames.valid(name),
                    supportingText = { if (!RecordingNames.valid(name)) Text("Enter a shorter name without filename punctuation") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (accepted && error != null && retryNameLocked) Text("Retry keeps this name to avoid saving the take twice.")
            }
        },
        confirmButton = { TextButton(enabled = !busy && RecordingNames.valid(name), onClick = { accepted = true; onSave(name.trim()) }) {
            Text(if (busy) "Working…" else "Save recording")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = { naming = false }) { Text("Cancel") } },
    )
}

@Composable
fun RecordingCloudIndicator(state: RecordingCloudState, enabled: Boolean, onRetry: () -> Unit) {
    IconButton(onClick = onRetry, enabled = enabled && state in setOf(RecordingCloudState.PENDING, RecordingCloudState.FAILED)) {
        when (state) {
            RecordingCloudState.SYNCED -> Icon(Icons.Default.CloudDone, "Synced to Cloud", tint = Color(0xFF43A047))
            RecordingCloudState.HISTORICAL_RECEIPT -> Icon(Icons.Default.CloudDone,
                "Previous cloud receipt; local copy needs verification", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            RecordingCloudState.UPLOADING -> CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Uploading to Cloud" }, strokeWidth = 2.dp)
            RecordingCloudState.PENDING -> Icon(Icons.Default.CloudOff, "Not uploaded / pending; tap to upload")
            RecordingCloudState.FAILED -> Icon(Icons.Default.CloudUpload, "Cloud upload failed; tap to retry", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun UploadWarning(message: String, warningId: String = message, onRecover: (() -> Unit)? = null,
    recoveryEnabled: Boolean = true, onClear: () -> Unit = {}) {
    var open by remember(warningId) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.Warning, "Recording warning", tint = MaterialTheme.colorScheme.error)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Recording needs attention") },
        text = { Text(if (onRecover != null) "$message\n\nRecover keeps existing recordings and saves verified interrupted takes under an available name."
            else "$message\n\nReview the recording before retrying the action. Retry a failed title update by renaming the recording again; failed audio uploads can be retried with the recording’s cloud icon.") },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                onRecover?.let { action -> TextButton(enabled = recoveryEnabled, onClick = { open = false; action() }) { Text("Recover") } }
                TextButton(onClick = { open = false; onClear() }) { Text("Clear") }
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
