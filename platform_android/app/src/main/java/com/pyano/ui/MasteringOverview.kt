// Concern: visualizes whole-song loudness and actual dynamics reduction | Non-concern: DSP | IO: (analysis, taps) -> graphs and preview seeks
package com.pyano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pyano.audio.*

@Composable
internal fun MasteringOverview(plan: MasteringPlan, onSeek: (Float) -> Unit) {
    val original = MaterialTheme.colorScheme.outline
    val processed = MaterialTheme.colorScheme.primary
    val compressor = MaterialTheme.colorScheme.tertiary
    val limiter = MaterialTheme.colorScheme.error
    fun loudness(value: Float?) = value?.let { "%.1f LUFS".format(it) } ?: "Silence"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Whole recording", style = MaterialTheme.typography.titleMedium)
            Text("${loudness(plan.before.lufs)} → ${loudness(plan.after.lufs)} · peak %.1f dBFS".format(plan.after.peakDb))
            Text("Automatic gain: input %+.1f dB · output %+.1f dB".format(plan.inputGain, plan.outputGain), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Original", color = original, style = MaterialTheme.typography.bodySmall)
                Text("Processed", color = processed, style = MaterialTheme.typography.bodySmall)
                Text("0 to −60 LUFS", style = MaterialTheme.typography.bodySmall)
            }
            MasteringGraph(listOf(plan.before.points.map { it.fraction to it.lufs } to original,
                plan.after.points.map { it.fraction to it.lufs } to processed), -60f, 0f,
                plan.settings.targetLufs.takeIf { plan.settings.normalize }, "Loudness throughout the recording", onSeek)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Compressor", color = compressor, style = MaterialTheme.typography.bodySmall)
                Text("Limiter", color = limiter, style = MaterialTheme.typography.bodySmall)
                Text("0 to 12 dB reduction", style = MaterialTheme.typography.bodySmall)
            }
            MasteringGraph(listOf(plan.after.points.map { it.fraction to it.compressorDb } to compressor,
                plan.after.points.map { it.fraction to it.limiterDb } to limiter), 0f, 12f, null,
                "Compressor and limiter gain reduction throughout the recording", onSeek, reduction = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0:00", style = MaterialTheme.typography.bodySmall)
                Text("%d:%02d".format(plan.durationMs / 60000, plan.durationMs / 1000 % 60), style = MaterialTheme.typography.bodySmall)
            }
            Text("Maximum reduction: compressor %.1f dB · limiter %.1f dB".format(
                plan.after.points.maxOfOrNull { it.compressorDb } ?: 0f, plan.after.points.maxOfOrNull { it.limiterDb } ?: 0f),
                style = MaterialTheme.typography.bodySmall)
            Text("Tap a graph to listen from that point.", style = MaterialTheme.typography.bodySmall)
            plan.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MasteringGraph(
    traces: List<Pair<List<Pair<Float, Float>>, Color>>, minimum: Float, maximum: Float,
    target: Float?, description: String, onSeek: (Float) -> Unit, reduction: Boolean = false,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }
        .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }) {
        fun y(value: Float): Float {
            val fraction = ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            return size.height * if (reduction) fraction else 1f - fraction
        }
        listOf(minimum, (minimum + maximum) / 2, maximum).forEach { value ->
            drawLine(grid, Offset(0f, y(value)), Offset(size.width, y(value)))
        }
        target?.let { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 2f) }
        traces.forEach { (points, color) ->
            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(0f, y(point.second))
                path.lineTo(point.first * size.width, y(point.second))
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}
