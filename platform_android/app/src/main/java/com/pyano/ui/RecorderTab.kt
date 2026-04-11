// RecorderTab: Audio recording controls, WAV playback, file management. NOT concerned with synth engine or navigation.
package com.pyano.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pyano.PyanoViewModel
import com.pyano.audio.RecordingInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecorderTab(viewModel: PyanoViewModel) {
    val isRecording by viewModel.isAudioRecording.collectAsState()
    val durationSec by viewModel.recordingDurationSec.collectAsState()
    val recordings by viewModel.savedRecordings.collectAsState()
    val outputLevel by viewModel.outputLevel.collectAsState()

    // Refresh recordings on first composition
    LaunchedEffect(Unit) { viewModel.refreshRecordings() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Record Controls ---
        RecordControlCard(
            isRecording = isRecording,
            durationSec = durationSec,
            outputLevel = outputLevel,
            onRecord = { viewModel.startAudioRecording() },
            onStop = { viewModel.stopAudioRecording() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Saved Recordings ---
        RecordingsListCard(
            recordings = recordings,
            onDelete = { viewModel.deleteRecording(it) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecordControlCard(
    isRecording: Boolean,
    durationSec: Int,
    outputLevel: Float,
    onRecord: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Timer display
            val minutes = durationSec / 60
            val seconds = durationSec % 60
            Text(
                text = "%02d:%02d".format(minutes, seconds),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Light,
                color = if (isRecording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Level meter
            if (isRecording) {
                val levelDb = if (outputLevel > 0.0001f) {
                    (20 * kotlin.math.log10(outputLevel)).coerceIn(-60f, 0f)
                } else -60f
                val levelFraction = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { levelFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (levelFraction > 0.9f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Recording...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "Tap to record",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Record / Stop button
            if (isRecording) {
                // Pulsing animation
                val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(72.dp)
                        .scale(pulseScale),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    // Stop icon: filled square
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.onError,
                                MaterialTheme.shapes.extraSmall
                            )
                    )
                }
            } else {
                FilledIconButton(
                    onClick = onRecord,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    // Record dot
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.onError,
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingsListCard(
    recordings: List<RecordingInfo>,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Saved Recordings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recordings yet\nRecordings are saved to Downloads/Pyano/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(recordings, key = { it.path }) { recording ->
                        RecordingRow(
                            recording = recording,
                            onDelete = { onDelete(recording.path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingInfo,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Cleanup media player on disposal
    DisposableEffect(recording.path) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    val dateStr = remember(recording.dateMs) {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            .format(Date(recording.dateMs))
    }
    val durationStr = remember(recording.durationMs) {
        val totalSec = recording.durationMs / 1000
        "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
    val sizeStr = remember(recording.sizeBytes) {
        when {
            recording.sizeBytes < 1024 -> "${recording.sizeBytes} B"
            recording.sizeBytes < 1024 * 1024 -> "%.1f KB".format(recording.sizeBytes / 1024.0)
            else -> "%.1f MB".format(recording.sizeBytes / (1024.0 * 1024.0))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Info column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$durationStr  |  $sizeStr  |  $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Play/Stop button
        IconButton(onClick = {
            if (isPlaying) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isPlaying = false
            } else {
                try {
                    val player = MediaPlayer()
                    player.setDataSource(recording.path)
                    player.setOnCompletionListener {
                        isPlaying = false
                        it.release()
                        mediaPlayer = null
                    }
                    player.prepare()
                    player.start()
                    mediaPlayer = player
                    isPlaying = true
                } catch (e: Exception) {
                    Toast.makeText(context, "Playback failed", Toast.LENGTH_SHORT).show()
                }
            }
        }) {
            if (isPlaying) {
                // Stop icon: small filled square
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.extraSmall
                        )
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Share button
        IconButton(onClick = {
            try {
                val file = File(recording.path)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share recording"))
            } catch (e: Exception) {
                Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Delete button
        IconButton(onClick = { showDeleteDialog = true }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Recording") },
            text = { Text("Delete \"${recording.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}
