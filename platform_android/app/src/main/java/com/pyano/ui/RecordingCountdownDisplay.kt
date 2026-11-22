// Concern: renders the recording count-in | Non-concern: capture timing | IO: (seconds) -> accessible countdown
package com.pyano.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pyano.PyanoViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

@Composable
fun RecordingCountdownDisplay(seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = "Recording starts in $seconds seconds"
    }, contentAlignment = Alignment.Center) {
        Text(seconds.toString(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun recordingCountdownOwner(viewModel: PyanoViewModel): Any {
    val owner = remember(viewModel) { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.cancelRecordingCountdown(owner)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.cancelRecordingCountdown(owner) }
    }
    return owner
}
