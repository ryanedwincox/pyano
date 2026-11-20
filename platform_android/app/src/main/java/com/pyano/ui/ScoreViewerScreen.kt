// Concern: presents offline PDF pages and gestures | Non-concern: rendering or audio ownership | IO: (reader state) -> navigation actions
package com.pyano.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pyano.viewer.*
import kotlin.math.min
import kotlin.math.max

@Composable
fun ScoreViewerScreen(controller: ScoreViewerController) {
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    BackHandler { controller.close() }
    DisposableEffect(controller, lifecycle, context) {
        val host = controller.registerHost(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.hostForeground(host, false)
            if (event == Lifecycle.Event.ON_START) controller.hostForeground(host, true)
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration: Configuration) { }
            override fun onLowMemory() = controller.trimMemory()
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) controller.trimMemory() }
        }
        lifecycle.addObserver(observer); context.registerComponentCallbacks(memory)
        onDispose { lifecycle.removeObserver(observer); context.unregisterComponentCallbacks(memory); controller.unregisterHost(host) }
    }
    ScoreViewerContent(state, controller::close, controller::page, controller::transform, controller::retry)
}

@Composable
fun ScoreViewerContent(
    state: ScoreViewerState,
    onBack: () -> Unit,
    onPage: (Int) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onRetry: () -> Unit,
) {
    val reading = state.reading
    val latestReading by rememberUpdatedState(reading)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = (state.frame as? BitmapPageFrame)?.bitmap
    val frame = state.frame
    val fit = if (frame == null || viewport.width == 0 || viewport.height == 0) 0f
        else min(viewport.width.toFloat() / frame.width, viewport.height.toFloat() / frame.height)
    val overflowX = max(0f, (fit * (frame?.width ?: 0) * reading.zoom - viewport.width) / 2)
    val overflowY = max(0f, (fit * (frame?.height ?: 0) * reading.zoom - viewport.height) / 2)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back to scores") }
                Text(state.source?.score?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPage(-1) }, enabled = state.pageCount > 0 && reading.page > 0) { Text("Previous") }
                Text(if (state.pageCount > 0) "Page ${reading.page + 1} of ${state.pageCount}" else "Opening PDF")
                Button(onClick = { onPage(1) }, enabled = state.pageCount > 0 && reading.page < state.pageCount - 1) { Text("Next") }
                TextButton(onClick = { onTransform(reading.zoom / 1.25f, reading.panX, reading.panY) }, enabled = state.pageCount > 0) { Text("Zoom out") }
                TextButton(onClick = { onTransform(reading.zoom * 1.25f, reading.panX, reading.panY) }, enabled = state.pageCount > 0) { Text("Zoom in") }
                TextButton(onClick = { onTransform(1f, 0f, 0f) }, enabled = state.pageCount > 0) { Text("Reset zoom") }
            }
            state.message?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.testTag("pdf-viewport").fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { viewport = it }
                .pointerInput(state.session, viewport, frame?.width, frame?.height) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val current = latestReading
                        val currentX = max(0f, (fit * (frame?.width ?: 0) * current.zoom - viewport.width) / 2)
                        val currentY = max(0f, (fit * (frame?.height ?: 0) * current.zoom - viewport.height) / 2)
                        val nextZoom = (current.zoom * zoom).takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: current.zoom
                        val nextX = max(0f, (fit * (frame?.width ?: 0) * nextZoom - viewport.width) / 2)
                        val nextY = max(0f, (fit * (frame?.height ?: 0) * nextZoom - viewport.height) / 2)
                        onTransform(nextZoom, if (nextX > 0) (current.panX * currentX + pan.x) / nextX else 0f,
                            if (nextY > 0) (current.panY * currentY + pan.y) / nextY else 0f)
                    }
                }, contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "PDF page ${reading.page + 1}",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = reading.zoom; scaleY = reading.zoom
                        translationX = reading.panX * overflowX; translationY = reading.panY * overflowY
                    })
            }
        }
    }
}
