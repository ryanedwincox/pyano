// LoopStationTab: Loop station controls — BPM, layer management, record/play/overdub, transport.
// NOT concerned with synth engine internals or navigation.
package com.pyano.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel
import com.pyano.audio.LoopEngine

private val barOptions = listOf(1, 2, 4, 8)

@Composable
fun LoopStationTab(viewModel: PyanoViewModel) {
    val loopBpm by viewModel.loopBpm.collectAsState()
    val loopLengthBars by viewModel.loopLengthBars.collectAsState()
    val loopRecording by viewModel.loopRecording.collectAsState()
    val loopPlaying by viewModel.loopPlaying.collectAsState()
    val loopLayerCounts by viewModel.loopLayerCounts.collectAsState()
    val loopLayerMuted by viewModel.loopLayerMuted.collectAsState()
    val loopLayerNames by viewModel.loopLayerNames.collectAsState()
    var renameLayerIndex by remember { mutableIntStateOf(-1) }
    val loopPosition by viewModel.loopPosition.collectAsState()
    val loopCountIn by viewModel.loopCountIn.collectAsState()
    val loopSyncBpm by viewModel.loopSyncBpm.collectAsState()
    val metronomeBpm by viewModel.metronomeBpm.collectAsState()
    val loopMetronome by viewModel.loopMetronome.collectAsState()
    val recordingLayerIndex by viewModel.loopRecordingLayerIndex.collectAsState()
    val hasAnyEvents by viewModel.loopHasAnyEvents.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // --- BPM ---
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BpmControl(
                    bpm = loopBpm,
                    onBpmChange = { viewModel.setLoopBpm(it) },
                    enabled = !loopSyncBpm
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Sync BPM toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (loopSyncBpm) "Synced to metronome ($metronomeBpm BPM)"
                               else "Independent BPM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = loopSyncBpm,
                        onCheckedChange = { viewModel.toggleLoopSyncBpm() }
                    )
                }
            }
        }

        // --- Loop Length ---
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Loop Length")
                Spacer(modifier = Modifier.height(8.dp))
                AdaptiveSelector(
                    items = barOptions,
                    selected = barOptions.find { it == loopLengthBars },
                    onSelect = { viewModel.setLoopLengthBars(it) },
                    label = { "$it bar${if (it > 1) "s" else ""}" },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }

        // --- Layers ---
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Layers")
                Spacer(modifier = Modifier.height(8.dp))

                for (i in 0 until LoopEngine.MAX_LAYERS) {
                    val eventCount = loopLayerCounts.getOrElse(i) { 0 }
                    val isActiveRecording = loopRecording &&
                        recordingLayerIndex == i

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        // Layer name (tap to rename)
                        Text(
                            text = loopLayerNames.getOrElse(i) { "Layer ${i + 1}" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .widthIn(min = 28.dp)
                                .clickable(enabled = eventCount > 0) {
                                    renameLayerIndex = i
                                },
                            maxLines = 1,
                        )

                        // Status badge
                        if (isActiveRecording) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text(
                                    text = "REC",
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        } else if (eventCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = "$eventCount events",
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "Empty",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Record button per layer
                        IconButton(
                            onClick = {
                                if (isActiveRecording) viewModel.stopLoopRecording()
                                else viewModel.startLoopRecordingLayer(i)
                            },
                            enabled = !loopRecording || isActiveRecording
                        ) {
                            Icon(
                                imageVector = if (isActiveRecording) Icons.Default.Stop
                                    else Icons.Default.FiberManualRecord,
                                contentDescription = if (isActiveRecording) "Stop recording"
                                    else "Record layer ${i + 1}",
                                tint = if (isActiveRecording) MaterialTheme.colorScheme.error
                                    else if (!loopRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }

                        // Mute toggle button
                        val isMuted = loopLayerMuted.getOrElse(i) { false }
                        IconButton(
                            onClick = { viewModel.toggleLoopLayerMuted(i) },
                            enabled = eventCount > 0 && !isActiveRecording
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (isMuted) "Unmute layer ${i + 1}" else "Mute layer ${i + 1}",
                                tint = if (eventCount > 0 && !isActiveRecording) {
                                    if (isMuted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                }
                            )
                        }

                        // Clear button
                        IconButton(
                            onClick = { viewModel.clearLoopLayer(i) },
                            enabled = eventCount > 0 && !isActiveRecording
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear layer ${i + 1}",
                                tint = if (eventCount > 0 && !isActiveRecording)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    }

                    if (i < LoopEngine.MAX_LAYERS - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 28.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        // --- Transport Progress ---
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Transport")
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { loopPosition },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (loopRecording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        // --- Controls ---
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Play/Stop button — uses primary chip style
                FilterChip(
                    selected = loopPlaying,
                    onClick = { viewModel.toggleLoopPlayback() },
                    label = {
                        Text(
                            text = if (loopPlaying) "Stop" else "Play",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (loopPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    enabled = loopPlaying || hasAnyEvents,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = BorderStroke(
                        1.dp,
                        if (loopPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Clear All + Count-in row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.clearAllLoops() },
                        enabled = loopLayerCounts.any { it > 0 }
                    ) {
                        Text("Clear All")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Metronome",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = loopMetronome,
                            onCheckedChange = { viewModel.toggleLoopMetronome() }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Count-in",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = loopCountIn,
                            onCheckedChange = { viewModel.toggleLoopCountIn() }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Rename dialog
    if (renameLayerIndex >= 0) {
        val currentName = loopLayerNames.getOrElse(renameLayerIndex) { "" }
        var textValue by remember(renameLayerIndex) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renameLayerIndex = -1 },
            title = { Text("Rename Layer") },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameLoopLayer(renameLayerIndex, textValue.trim().ifEmpty { "Layer ${renameLayerIndex + 1}" })
                    renameLayerIndex = -1
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameLayerIndex = -1 }) { Text("Cancel") }
            },
        )
    }
}
