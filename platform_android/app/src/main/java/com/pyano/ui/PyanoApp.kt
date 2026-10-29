// PyanoApp: Top-level scaffold with tab navigation (Synth, Metronome, Loops, Recorder, Devices). NOT concerned with tab content.
package com.pyano.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel

/** Tab definitions for top-level navigation. Order matches persisted index. */
private enum class AppTab(val title: String) {
    Synth("Synth"),
    Metronome("Metronome"),
    Loops("Loops"),
    Recorder("Recorder"),
    Devices("Devices"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyanoApp(viewModel: PyanoViewModel, onOpenApps: (() -> Unit)? = null) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val outputProblem by viewModel.outputProblem.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Pyano") },
                    actions = {
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
                        onClick = { viewModel.setSelectedTab(AppTab.Devices.ordinal) },
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
                AppTab.Devices.ordinal -> DevicesTab(viewModel)
                else -> SynthTab(viewModel) // Fallback for corrupt prefs
            }
        }
    }
}
