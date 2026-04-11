// PyanoApp: Top-level scaffold with tab navigation (Synth, Metronome, Loops, Recorder). NOT concerned with tab content.
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyanoApp(viewModel: PyanoViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()

    Scaffold(
        topBar = {
            Column {
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
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { viewModel.setSelectedTab(index) },
                            text = { Text(tab.title) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> SynthTab(viewModel)
                1 -> MetronomeTab(viewModel)
                2 -> LoopStationTab(viewModel)
                3 -> RecorderTab(viewModel)
                else -> SynthTab(viewModel) // Fallback for corrupt prefs
            }
        }
    }
}
