// LoopStationTab: Loop station controls — BPM, layer management, record/play/overdub, transport.
// NOT concerned with synth engine internals or navigation.
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
    val loopPosition by viewModel.loopPosition.collectAsState()
    val loopCountIn by viewModel.loopCountIn.collectAsState()
    val loopSyncBpm by viewModel.loopSyncBpm.collectAsState()
    val metronomeBpm by viewModel.metronomeBpm.collectAsState()
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (bars in barOptions) {
                        FilterChip(
                            selected = loopLengthBars == bars,
                            onClick = { viewModel.setLoopLengthBars(bars) },
                            label = { Text("$bars bar${if (bars > 1) "s" else ""}") }
                        )
                    }
                }
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
                        // Layer number
                        Text(
                            text = "${i + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(28.dp)
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
                // Record + Play/Stop row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    // Record button
                    val hasEmptyLayer = loopLayerCounts.any { it == 0 }
                    FilledTonalButton(
                        onClick = {
                            if (loopRecording) viewModel.stopLoopRecording()
                            else viewModel.startLoopRecording()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (loopRecording) MaterialTheme.colorScheme.error
                                             else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (loopRecording) MaterialTheme.colorScheme.onError
                                           else MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = loopRecording || hasEmptyLayer
                    ) {
                        Text(
                            text = if (loopRecording) "Stop Rec" else "Record",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Play/Stop button
                    FilledTonalButton(
                        onClick = { viewModel.toggleLoopPlayback() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (loopPlaying) MaterialTheme.colorScheme.error
                                             else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (loopPlaying) MaterialTheme.colorScheme.onError
                                           else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = loopPlaying || hasAnyEvents
                    ) {
                        Text(
                            text = if (loopPlaying) "Stop" else "Play",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

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
}
