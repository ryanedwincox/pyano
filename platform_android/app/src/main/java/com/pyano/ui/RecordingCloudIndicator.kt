// Concern: shows recording save decisions and private sync state | Non-concern: publishing | IO: (take and cloud state) -> controls
package com.pyano.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
fun RecordingCloudIndicator(state: RecordingCloudState, enabled: Boolean, onRetry: () -> Unit) {
    IconButton(onClick = onRetry, enabled = enabled && state in setOf(RecordingCloudState.PENDING, RecordingCloudState.FAILED)) {
        when (state) {
            RecordingCloudState.SYNCED -> Icon(Icons.Default.CloudDone, "Synced to Cloud", tint = Color(0xFF43A047))
            RecordingCloudState.UPLOADING -> CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Uploading to Cloud" }, strokeWidth = 2.dp)
            RecordingCloudState.PENDING -> Icon(Icons.Default.CloudOff, "Not uploaded / pending; tap to upload")
            RecordingCloudState.FAILED -> Icon(Icons.Default.CloudUpload, "Cloud upload failed; tap to retry", tint = MaterialTheme.colorScheme.error)
        }
    }
}
