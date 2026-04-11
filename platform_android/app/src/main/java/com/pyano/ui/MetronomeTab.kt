// MetronomeTab: Tempo control, beat indicator, tap tempo, time signature. NOT concerned with synth engine or navigation.
package com.pyano.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel

private data class TimeSigOption(val beats: Int, val label: String)

private val timeSigOptions = listOf(
    TimeSigOption(2, "2/4"),
    TimeSigOption(3, "3/4"),
    TimeSigOption(4, "4/4"),
    TimeSigOption(6, "6/8"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeTab(viewModel: PyanoViewModel) {
    val bpm by viewModel.metronomeBpm.collectAsState()
    val timeSig by viewModel.metronomeTimeSig.collectAsState()
    val running by viewModel.metronomeRunning.collectAsState()
    val currentBeat by viewModel.metronomeBeat.collectAsState()
    val clickType by viewModel.metronomeClickType.collectAsState()
    val volume by viewModel.metronomeVolume.collectAsState()
    var soundMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // BPM Display + Controls
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BpmControl(
                    bpm = bpm,
                    onBpmChange = { viewModel.setMetronomeBpm(it) }
                )
            }
        }

        // Beat Indicator
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SectionHeader("Beat")
                Spacer(modifier = Modifier.height(8.dp))
                // The beat indicator shows which beat just played.
                // currentBeat from the engine is the NEXT beat (post-increment),
                // so the beat that just played is (currentBeat - 1 + timeSig) % timeSig.
                val justPlayed = (currentBeat - 1 + timeSig) % timeSig
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in 0 until timeSig) {
                        val isActive = running && (i == justPlayed)

                        val scale by animateFloatAsState(
                            targetValue = if (isActive) 1.3f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessHigh
                            ),
                            label = "beatScale$i"
                        )

                        val color = when {
                            isActive && i == 0 -> MaterialTheme.colorScheme.primary
                            isActive -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(32.dp)
                                .scale(scale)
                                .background(color, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Time Signature
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Time Signature")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (option in timeSigOptions) {
                        FilterChip(
                            selected = timeSig == option.beats,
                            onClick = { viewModel.setMetronomeTimeSig(option.beats) },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
        }

        // Sound + Volume
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Sound")
                Spacer(modifier = Modifier.height(8.dp))
                val sounds = viewModel.metronomeSounds
                val selectedIndex = clickType.coerceIn(0, sounds.lastIndex)
                val selectedLabel = sounds[selectedIndex].name
                ExposedDropdownMenuBox(
                    expanded = soundMenuExpanded,
                    onExpandedChange = { soundMenuExpanded = !soundMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Click sound") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = soundMenuExpanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = soundMenuExpanded,
                        onDismissRequest = { soundMenuExpanded = false }
                    ) {
                        sounds.forEachIndexed { idx, sound ->
                            DropdownMenuItem(
                                text = { Text(sound.name) },
                                onClick = {
                                    viewModel.setMetronomeClickType(idx)
                                    soundMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Volume")
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = volume,
                        onValueChange = { viewModel.setMetronomeVolume(it) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(min = 48.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        // Controls: Start/Stop + Tap Tempo
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    // Start/Stop button — matches the primary "selected chip" style
                    // used by selector buttons on the Synth tab (filled primary).
                    Button(
                        onClick = { viewModel.toggleMetronome() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) MaterialTheme.colorScheme.error
                                             else MaterialTheme.colorScheme.primary,
                            contentColor = if (running) MaterialTheme.colorScheme.onError
                                           else MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text(
                            text = if (running) "Stop" else "Start",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Tap Tempo button
                    OutlinedButton(
                        onClick = { viewModel.tapTempo() },
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text(
                            text = "Tap Tempo",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
