package com.pyano.ui

import android.media.midi.MidiDeviceInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyanoApp(viewModel: PyanoViewModel) {
    val soundFontName by viewModel.soundFontName.collectAsState()
    val midiDevices by viewModel.midiDevices.collectAsState()
    val selectedMidiDevice by viewModel.selectedMidiDevice.collectAsState()
    val midiStatus by viewModel.midiStatus.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val selectedPresetIndex by viewModel.selectedPresetIndex.collectAsState()
    val gain by viewModel.gain.collectAsState()
    val bufferSize by viewModel.bufferSize.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Reverb state
    val reverbOn by viewModel.reverbOn.collectAsState()
    val reverbRoom by viewModel.reverbRoom.collectAsState()
    val reverbDamp by viewModel.reverbDamp.collectAsState()
    val reverbWidth by viewModel.reverbWidth.collectAsState()
    val reverbLevel by viewModel.reverbLevel.collectAsState()

    // Chorus state
    val chorusOn by viewModel.chorusOn.collectAsState()
    val chorusVoices by viewModel.chorusVoices.collectAsState()
    val chorusLevel by viewModel.chorusLevel.collectAsState()
    val chorusSpeed by viewModel.chorusSpeed.collectAsState()
    val chorusDepth by viewModel.chorusDepth.collectAsState()
    val chorusType by viewModel.chorusType.collectAsState()

    // Audio output
    val audioOutputs by viewModel.audioOutputs.collectAsState()
    val selectedAudioOutput by viewModel.selectedAudioOutput.collectAsState()

    // Activity monitors
    val midiInputActive by viewModel.midiInputLevel.collectAsState()
    val outputLevel by viewModel.outputLevel.collectAsState()

    // SoundFont browser
    var showSfBrowser by remember { mutableStateOf(false) }

    // Dropdown states
    var midiDropdownExpanded by remember { mutableStateOf(false) }
    var audioOutputDropdownExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pyano") },
                actions = {
                    Image(
                        painter = painterResource(id = com.pyano.R.drawable.ic_pyano_logo),
                        contentDescription = "Pyano logo",
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(40.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading SoundFont...")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // SoundFont section
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // SoundFont
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SoundFont",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(88.dp)
                            )
                            Text(
                                text = soundFontName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(onClick = { showSfBrowser = true }) {
                                Text("Browse")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // MIDI Device
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "MIDI",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(88.dp)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshMidiDevices()
                                        midiDropdownExpanded = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(midiStatus)
                                }
                                DropdownMenu(
                                    expanded = midiDropdownExpanded,
                                    onDismissRequest = { midiDropdownExpanded = false }
                                ) {
                                    if (midiDevices.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("No MIDI devices found") },
                                            onClick = { midiDropdownExpanded = false }
                                        )
                                    }
                                    midiDevices.forEach { device ->
                                        val name = viewModel.let {
                                            device.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                                                ?: "Unknown"
                                        }
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                viewModel.connectMidiDevice(device)
                                                midiDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Audio Output
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Output",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(88.dp)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshAudioOutputs()
                                        audioOutputDropdownExpanded = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(selectedAudioOutput?.name ?: "Default")
                                }
                                DropdownMenu(
                                    expanded = audioOutputDropdownExpanded,
                                    onDismissRequest = { audioOutputDropdownExpanded = false },
                                    modifier = Modifier.heightIn(max = 300.dp)
                                ) {
                                    audioOutputs.forEach { output ->
                                        DropdownMenuItem(
                                            text = { Text(output.name) },
                                            onClick = {
                                                viewModel.setAudioOutput(output)
                                                audioOutputDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Activity Monitors
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // MIDI Input indicator
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("MIDI In", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(end = 8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            if (midiInputActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            shape = MaterialTheme.shapes.small
                                        )
                                )
                            }

                            // Output level meter
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(start = 24.dp)
                            ) {
                                Text("Out", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(end = 8.dp))
                                val levelDb = if (outputLevel > 0.0001f) {
                                    (20 * kotlin.math.log10(outputLevel)).coerceIn(-60f, 0f)
                                } else -60f
                                val levelFraction = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { levelFraction },
                                    modifier = Modifier.weight(1f).height(8.dp),
                                    color = if (levelDb > -3f) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                )
                            }
                        }
                    }
                }

                // Gain + Buffer
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionHeader("Gain / Buffer")
                        LabeledSlider("Gain", gain, 0f..10f, "%.1f", { viewModel.setGain(it) })
                        BufferSizeSlider(bufferSize, { viewModel.setBufferSize(it) })
                    }
                }

                // Reverb
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionHeader("Reverb", reverbOn) { viewModel.setReverbOn(it) }
                        if (reverbOn) {
                            LabeledSlider("Room", reverbRoom, 0f..1f, "%.2f", { viewModel.setReverbRoom(it) })
                            LabeledSlider("Damp", reverbDamp, 0f..1f, "%.2f", { viewModel.setReverbDamp(it) })
                            LabeledSlider("Width", reverbWidth, 0f..100f, "%.1f", { viewModel.setReverbWidth(it) })
                            LabeledSlider("Level", reverbLevel, 0f..1f, "%.2f", { viewModel.setReverbLevel(it) })
                        }
                    }
                }

                // Chorus
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionHeader("Chorus", chorusOn) { viewModel.setChorusOn(it) }
                        if (chorusOn) {
                            IntSlider("Voices", chorusVoices, 0..99, { viewModel.setChorusVoices(it) })
                            LabeledSlider("Level", chorusLevel, 0f..10f, "%.1f", { viewModel.setChorusLevel(it) })
                            LabeledSlider("Speed", chorusSpeed, 0.29f..5f, "%.2f Hz", { viewModel.setChorusSpeed(it) })
                            LabeledSlider("Depth", chorusDepth, 0f..21f, "%.1f ms", { viewModel.setChorusDepth(it) })
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Type",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(72.dp)
                                )
                                FilterChip(
                                    selected = chorusType == 0,
                                    onClick = { viewModel.setChorusType(0) },
                                    label = { Text("Sine") },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                FilterChip(
                                    selected = chorusType == 1,
                                    onClick = { viewModel.setChorusType(1) },
                                    label = { Text("Triangle") }
                                )
                            }
                        }
                    }
                }

                // Instruments — only show if soundfont has more than 1 preset
                if (presets.size > 1) {
                    var instrumentDropdownExpanded by remember { mutableStateOf(false) }
                    val selectedPreset = presets.getOrNull(selectedPresetIndex)

                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            SectionHeader("Instruments")
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                OutlinedButton(
                                    onClick = { instrumentDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        selectedPreset?.let {
                                            "${String.format("%03d", it.program)}: ${it.name}"
                                        } ?: "Select instrument"
                                    )
                                }
                                DropdownMenu(
                                    expanded = instrumentDropdownExpanded,
                                    onDismissRequest = { instrumentDropdownExpanded = false },
                                    modifier = Modifier.heightIn(max = 400.dp)
                                ) {
                                    presets.forEachIndexed { index, preset ->
                                        DropdownMenuItem(
                                            text = {
                                                Text("${String.format("%03d", preset.program)}: ${preset.name}")
                                            },
                                            onClick = {
                                                viewModel.selectPreset(index)
                                                instrumentDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // SoundFont browser bottom sheet
    if (showSfBrowser) {
        SoundFontBrowser(
            viewModel = viewModel,
            onDismiss = { showSfBrowser = false }
        )
    }
}
