// Concern: renders the mastering workspace | Non-concern: DSP implementation | IO: (recording and settings) -> mastering actions
package com.pyano.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pyano.audio.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import com.pyano.audio.BiquadFilter
import com.pyano.audio.MasteringChain
import com.pyano.audio.MasteringPlayer
import com.pyano.audio.RecordingInfo
import com.pyano.audio.exportMaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

@Composable
fun MasteringScreen(
    recording: RecordingInfo,
    onDismiss: () -> Unit,
    onExportComplete: () -> Unit
) {
    val player = remember(recording.path) { MasteringPlayer() }
    val scope = rememberCoroutineScope()
    var settings by remember(recording.path) { mutableStateOf(MasteringSettings()) }
    var plan by remember(recording.path) { mutableStateOf<MasteringPlan?>(null) }
    var analysisProgress by remember(recording.path) { mutableFloatStateOf(0f) }
    var analysisError by remember(recording.path) { mutableStateOf<String?>(null) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(recording.path, settings) {
        player.stop()
        plan = null
        analysisError = null
        analysisProgress = 0f
        try {
            delay(300)
            player.load(recording.path)
            val result = analyzeMastering(File(recording.path), settings) { analysisProgress = it }
            currentCoroutineContext().ensureActive()
            player.setProcessing(result.chain())
            plan = result
            analysisProgress = -1f
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { analysisError = error.message ?: "Could not analyze recording. Reopen it to retry."; analysisProgress = -1f }
    }

    // Collect player state flows
    val isPlaying by player.isPlaying.collectAsState()
    val playbackError by player.playbackError.collectAsState()
    val playbackPos by player.playbackPosition.collectAsState()
    val leftPeakDb by player.leftPeakDb.collectAsState()
    val rightPeakDb by player.rightPeakDb.collectAsState()
    val leftClip by player.leftClip.collectAsState()
    val rightClip by player.rightClip.collectAsState()
    val compressorGrDb by player.compressorGrDb.collectAsState()
    val limiterGrDb by player.limiterGrDb.collectAsState()
    val limiterActive by player.limiterActive.collectAsState()

    // Export state
    var exportProgress by remember { mutableFloatStateOf(-1f) }
    var exportedFile by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Top Bar ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                    Text(
                        text = recording.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                }

                val isExporting = exportProgress >= 0f
                val ready = plan?.settings == settings && analysisProgress < 0f && analysisError == null
                MasteringControls(settings, { if (!isExporting) { player.stop(); settings = it } }, !isExporting,
                    plan.takeIf { ready }, analysisProgress, analysisError, compressorGrDb, limiterGrDb, limiterActive,
                    onSeek = { fraction -> if (ready && !isExporting) { player.seekTo(fraction); if (!isPlaying) player.play(scope) } })
                StereoMetersSection(leftPeakDb, rightPeakDb, leftClip, rightClip) { player.resetClip() }
                playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TransportSection(isPlaying, playbackPos, recording.durationMs, player, scope, isExporting || !ready)
                ExportSection(
                    recording = recording,
                    chain = player.chain,
                    ready = ready,
                    sourceIdentity = plan?.sourceIdentity,
                    scope = scope,
                    exportProgress = exportProgress,
                    exportedFile = exportedFile,
                    exportError = exportError,
                    onExportStart = { player.stop(); exportProgress = 0f; exportError = null },
                    onProgress = { exportProgress = it },
                    onExportDone = { file ->
                        exportProgress = -1f
                        exportedFile = file
                        onExportComplete()
                    },
                    onExportError = { msg ->
                        exportProgress = -1f
                        exportError = msg
                    }
                )
            }
        }
    }
}

// ── EQ Section ──

@Composable
internal fun EqSection(settings: MasteringSettings, onChange: (MasteringSettings) -> Unit, enabled: Boolean, sampleRate: Int) {
    val lowFreq = settings.eq[0].frequency; val lowGain = settings.eq[0].gain
    val midFreq = settings.eq[1].frequency; val midGain = settings.eq[1].gain
    val highFreq = settings.eq[2].frequency; val highGain = settings.eq[2].gain
    fun update(band: Int, frequency: Float, gain: Float) = onChange(settings.copy(
        eq = settings.eq.mapIndexed { index, value -> if (index == band) MasteringEq(frequency, gain) else value }))

    val curvePoints = remember(lowFreq, lowGain, midFreq, midGain, highFreq, highGain, sampleRate) {
        val sr = sampleRate
        val low = BiquadFilter.lowShelf(lowFreq.coerceAtMost(sr * .45f), lowGain, sr)
        val mid = BiquadFilter.peakEQ(midFreq.coerceAtMost(sr * .45f), midGain, 1f, sr)
        val high = BiquadFilter.highShelf(highFreq.coerceAtMost(sr * .45f), highGain, sr)
        FloatArray(200) { i ->
            val freq = 20f * (20000f / 20f).pow(i.toFloat() / 199f)
            low.evaluateMagnitudeDb(freq, sr) +
                mid.evaluateMagnitudeDb(freq, sr) +
                high.evaluateMagnitudeDb(freq, sr)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Equalizer", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            EqCurveCanvas(curvePoints)
            Spacer(Modifier.height(8.dp))

            Text("Low", style = MaterialTheme.typography.bodyMedium)
            LabeledSlider("Freq", lowFreq, 60f..500f, "%.0f Hz",
                enabled = enabled, onValueChange = { update(0, it, lowGain) })
            LabeledSlider("Gain", lowGain, -12f..12f, "%+.1f dB",
                enabled = enabled, onValueChange = { update(0, lowFreq, it) })

            Spacer(Modifier.height(4.dp))
            Text("Mid", style = MaterialTheme.typography.bodyMedium)
            LabeledSlider("Freq", midFreq, 200f..8000f, "%.0f Hz",
                enabled = enabled, onValueChange = { update(1, it, midGain) })
            LabeledSlider("Gain", midGain, -12f..12f, "%+.1f dB",
                enabled = enabled, onValueChange = { update(1, midFreq, it) })

            Spacer(Modifier.height(4.dp))
            Text("High", style = MaterialTheme.typography.bodyMedium)
            LabeledSlider("Freq", highFreq, 2000f..16000f, "%.0f Hz",
                enabled = enabled, onValueChange = { update(2, it, highGain) })
            LabeledSlider("Gain", highGain, -12f..12f, "%+.1f dB",
                enabled = enabled, onValueChange = { update(2, highFreq, it) })
        }
    }
}

// ── Compressor Section ──

@Composable
internal fun CompressorSection(settings: MasteringSettings, onChange: (MasteringSettings) -> Unit, grDb: Float, enabled: Boolean) {
    val compression = settings.compression
    val threshold = compression.threshold; val ratio = compression.ratio
    val attack = compression.attack; val release = compression.release; val makeup = compression.makeup

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Compressor", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            GainReductionMeter(grDb = grDb, maxDb = 20f, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(8.dp))
            LabeledSlider("Thresh", threshold, -60f..0f, "%.1f dB",
                enabled = enabled, onValueChange = { onChange(settings.copy(compression = compression.copy(threshold = it))) })
            LabeledSlider("Ratio", ratio, 1f..20f, "%.1f:1",
                enabled = enabled, onValueChange = { onChange(settings.copy(compression = compression.copy(ratio = it))) })
            LabeledSlider("Attack", attack, 0.1f..100f, "%.1f ms",
                enabled = enabled, onValueChange = { onChange(settings.copy(compression = compression.copy(attack = it))) })
            LabeledSlider("Release", release, 10f..1000f, "%.0f ms",
                enabled = enabled, onValueChange = { onChange(settings.copy(compression = compression.copy(release = it))) })
            LabeledSlider("Makeup", makeup, 0f..24f, "%.1f dB",
                enabled = enabled, onValueChange = { onChange(settings.copy(compression = compression.copy(makeup = it))) })
        }
    }
}

// ── Limiter Section ──

@Composable
internal fun LimiterSection(settings: MasteringSettings, onChange: (MasteringSettings) -> Unit, grDb: Float, active: Boolean, enabled: Boolean) {
    val ceiling = settings.ceiling; val release = settings.limiterRelease

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Limiter", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.error
                            else Color.Transparent
                        )
                )
            }
            Spacer(Modifier.height(8.dp))
            GainReductionMeter(grDb = grDb, maxDb = 20f, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            LabeledSlider("Ceiling", ceiling, -12f..0f, "%.1f dB",
                enabled = enabled, onValueChange = { onChange(settings.copy(ceiling = it)) })
            LabeledSlider("Release", release, 1f..500f, "%.0f ms",
                enabled = enabled, onValueChange = { onChange(settings.copy(limiterRelease = it)) })
        }
    }
}

// ── Stereo Meters Section ──

@Composable
private fun StereoMetersSection(
    leftPeakDb: Float,
    rightPeakDb: Float,
    leftClip: Boolean,
    rightClip: Boolean,
    onResetClip: () -> Unit
) {
    val errorColor = MaterialTheme.colorScheme.error
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Output Meters", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            StereoMeterRow("L", leftPeakDb, leftClip, errorColor, outlineColor, onResetClip)
            Spacer(Modifier.height(4.dp))
            StereoMeterRow("R", rightPeakDb, rightClip, errorColor, outlineColor, onResetClip)
        }
    }
}

// ── Transport Section ──

@Composable
private fun TransportSection(
    isPlaying: Boolean,
    playbackPos: Float,
    totalMs: Long,
    player: MasteringPlayer,
    scope: CoroutineScope,
    isExporting: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Transport", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isPlaying) player.stop() else player.play(scope)
                    },
                    enabled = !isExporting
                ) {
                    if (isPlaying) {
                        // Stop icon: filled square
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface,
                                    MaterialTheme.shapes.extraSmall
                                )
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                }
                var seeking by remember { mutableStateOf(false) }
                var seekValue by remember { mutableFloatStateOf(0f) }
                Slider(
                    value = if (seeking) seekValue else playbackPos,
                    enabled = !isExporting,
                    onValueChange = { seeking = true; seekValue = it },
                    onValueChangeFinished = {
                        player.seekTo(seekValue)
                        seeking = false
                    },
                    modifier = Modifier.weight(1f)
                )
                val elapsed = (playbackPos * totalMs).toLong()
                Text(
                    text = "${formatTime(elapsed)} / ${formatTime(totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

// ── Export Section ──

@Composable
private fun ExportSection(
    recording: RecordingInfo,
    chain: MasteringChain,
    ready: Boolean,
    sourceIdentity: String?,
    scope: CoroutineScope,
    exportProgress: Float,
    exportedFile: String?,
    exportError: String?,
    onExportStart: () -> Unit,
    onProgress: (Float) -> Unit,
    onExportDone: (String) -> Unit,
    onExportError: (String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Export", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onExportStart()
                    val exportChain = chain.copyWithCurrentParams()
                    scope.launch {
                        try {
                            val result = exportMaster(recording.path, exportChain, sourceIdentity, File(context.filesDir, "take-publications"), onProgress)
                            onExportDone(result.substringAfterLast('/'))
                        } catch (e: CancellationException) {
                            onExportError("Export cancelled")
                            throw e
                        } catch (e: Exception) {
                            Log.e("Pyano", "Export failed", e)
                            onExportError(e.message ?: "Export failed")
                        }
                    }
                },
                enabled = exportProgress < 0f && ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Master")
            }
            if (exportProgress >= 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            exportError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            exportedFile?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── EQ Frequency Response Curve ──

@Composable
private fun EqCurveCanvas(curvePoints: FloatArray) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gridColor = onSurface.copy(alpha = 0.15f)
    val fillColor = primary.copy(alpha = 0.2f)

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val numPoints = curvePoints.size

        fun dbToY(db: Float): Float = h / 2f - (db / 12f) * (h / 2f)
        fun indexToX(i: Int): Float = i.toFloat() / (numPoints - 1) * w

        // Grid lines at 0, ±6, ±12 dB
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = dbToY(db)
            drawLine(
                color = if (db == 0f) gridColor.copy(alpha = 0.4f) else gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 1.5f else 0.5f
            )
        }

        // Build curve path
        val curvePath = Path()
        val fillPath = Path()
        val zeroY = dbToY(0f)

        fillPath.moveTo(0f, zeroY)

        for (i in curvePoints.indices) {
            val x = indexToX(i)
            val y = dbToY(curvePoints[i].coerceIn(-12f, 12f))
            if (i == 0) {
                curvePath.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(w, zeroY)
        fillPath.close()

        drawPath(fillPath, fillColor, style = Fill)
        drawPath(curvePath, primary, style = Stroke(width = 2f))
    }
}

// ── Gain Reduction Meter ──

@Composable
private fun GainReductionMeter(grDb: Float, maxDb: Float, color: Color) {
    val fraction = (grDb / maxDb).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("GR", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(28.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .align(Alignment.CenterEnd)
                    .background(color, MaterialTheme.shapes.small)
            )
        }
        Text(
            text = String.format("%.1f dB", -grDb),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp).padding(start = 4.dp)
        )
    }
}

// ── Stereo Level Meter Row ──

@Composable
private fun StereoMeterRow(
    label: String,
    peakDb: Float,
    clip: Boolean,
    clipActiveColor: Color,
    clipInactiveColor: Color,
    onResetClip: () -> Unit
) {
    val fraction = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val meterBg = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(16.dp))
        Canvas(modifier = Modifier.weight(1f).height(12.dp)) {
            drawMeterBar(fraction, meterBg)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(10.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(if (clip) clipActiveColor else clipInactiveColor)
                .clickable { onResetClip() }
        )
        Text(
            text = String.format("%.1f", peakDb),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp)
        )
    }
}

// Meter colors — fixed palette intentional for audio metering (green→yellow→red) regardless of theme
private val MeterGreen = Color(0xFF4CAF50)
private val MeterYellow = Color(0xFFFFEB3B)
private val MeterRed = Color(0xFFF44336)

private fun DrawScope.drawMeterBar(fraction: Float, bgColor: Color) {
    val w = size.width
    val h = size.height
    val filledWidth = w * fraction

    drawRect(bgColor, size = Size(w, h))

    if (filledWidth <= 0f) return

    // Green: 0–70%, Yellow: 70–90%, Red: 90–100%
    val greenEnd = w * 0.7f
    val yellowEnd = w * 0.9f

    val greenWidth = filledWidth.coerceAtMost(greenEnd)
    if (greenWidth > 0f) {
        drawRect(MeterGreen, size = Size(greenWidth, h))
    }

    if (filledWidth > greenEnd) {
        val yellowWidth = (filledWidth - greenEnd).coerceAtMost(yellowEnd - greenEnd)
        drawRect(MeterYellow, topLeft = Offset(greenEnd, 0f), size = Size(yellowWidth, h))
    }

    if (filledWidth > yellowEnd) {
        val redWidth = filledWidth - yellowEnd
        drawRect(MeterRed, topLeft = Offset(yellowEnd, 0f), size = Size(redWidth, h))
    }
}

// ── Utilities ──

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
