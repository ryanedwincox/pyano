// PyanoApp: Top-level scaffold with tab navigation (Synth, Metronome, Loops, Recorder) and the Devices pop-up. NOT concerned with tab content.
package com.pyano.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pyano.PyanoViewModel

/** Tab definitions for top-level navigation. Order matches persisted index. */
private enum class AppTab(val title: String) {
    Synth("Synth"),
    Metronome("Metronome"),
    Loops("Loops"),
    Recorder("Recorder"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyanoApp(viewModel: PyanoViewModel, onOpenApps: (() -> Unit)? = null) {
    // Clamped: a saved index can point past the end (Devices used to be a tab).
    val selectedTab = viewModel.selectedTab.collectAsState().value
        .takeIf { it in AppTab.entries.indices } ?: AppTab.Synth.ordinal
    val outputProblem by viewModel.outputProblem.collectAsState()
    var showDevices by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Pyano") },
                    actions = {
                        TextButton(onClick = { showDevices = true }) {
                            Text(
                                "Devices",
                                color = if (outputProblem != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Pyano can be the home app; this reaches the regular launcher.
                        if (onOpenApps != null) {
                            TextButton(onClick = onOpenApps) { Text("Apps") }
                        }
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
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { viewModel.setSelectedTab(index) },
                            text = { Text(tab.title) },
                        )
                    }
                }
                // The chosen output dropped out: say which layer lost it, wherever the user is.
                outputProblem?.let { problem ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        onClick = { showDevices = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            problem,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.Synth.ordinal -> SynthTab(viewModel)
                AppTab.Metronome.ordinal -> MetronomeTab(viewModel)
                AppTab.Loops.ordinal -> LoopStationTab(viewModel)
                AppTab.Recorder.ordinal -> RecorderTab(viewModel)
            }
        }
    }

    if (showDevices) {
        Dialog(
            onDismissRequest = { showDevices = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showDevices = false }) { Text("Close") }
                    }
                    DevicesPanel(viewModel)
                }
            }
        }
    }
}
