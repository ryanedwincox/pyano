// Concern: presents simple presets and editable advanced mastering controls | Non-concern: DSP | IO: (shared settings, analysis) -> editing actions
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pyano.audio.*

@Composable
internal fun MasteringControls(
    settings: MasteringSettings, onChange: (MasteringSettings) -> Unit, enabled: Boolean,
    plan: MasteringPlan?, progress: Float, error: String?, compressorDb: Float = 0f,
    limiterDb: Float = 0f, limiterActive: Boolean = false, onSeek: (Float) -> Unit = {},
) {
    var advanced by remember { mutableStateOf(false) }
    TabRow(selectedTabIndex = if (advanced) 1 else 0) {
        Tab(selected = !advanced, onClick = { advanced = false }, text = { Text("Simple") })
        Tab(selected = advanced, onClick = { advanced = true }, text = { Text("Advanced") })
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Match recording volume", style = MaterialTheme.typography.titleMedium)
                    Text("Target: %.0f LUFS".format(settings.targetLufs), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.normalize, enabled = enabled, onCheckedChange = { onChange(settings.copy(normalize = it)) })
            }
            Text("Compression", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompressionPreset.entries.forEach { preset ->
                    FilterChip(selected = settings.preset == preset, enabled = enabled,
                        onClick = { onChange(settings.copy(compression = preset.compression)) }, label = { Text(preset.title) })
                }
            }
            Text(settings.preset?.description ?: "Custom compression · adjusted in Advanced", style = MaterialTheme.typography.bodySmall)
            Text("Final limiter: %.1f dBFS · original recording is kept".format(settings.ceiling), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (advanced) {
        LabeledSlider("Target", settings.targetLufs, -30f..-10f, "%.1f LUFS", enabled = enabled && settings.normalize,
            onValueChange = { onChange(settings.copy(targetLufs = it)) })
        EqSection(settings, onChange, enabled, plan?.sampleRate ?: 48000)
        CompressorSection(settings, onChange, compressorDb, enabled)
        LimiterSection(settings, onChange, limiterDb, limiterActive, enabled)
    }
    if (progress >= 0f) {
        Text("Analyzing recording silently… %.0f%%".format(progress * 100), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    plan?.let { MasteringOverview(it, onSeek) }
}
