// Concern: presents offline PDF pages and gestures | Non-concern: rendering or audio ownership | IO: (reader state) -> navigation actions
package com.pyano.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pyano.viewer.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

private data class PresentedPage(val page: Int, val bitmap: Bitmap)

@Composable
fun ScoreViewerScreen(controller: ScoreViewerController, recording: Boolean = false, recordEnabled: Boolean = true, countdown: Int = 0, onRecord: (() -> Unit)? = null) {
    val state by controller.state.collectAsState()
    val (nightLight, cycleNightLight) = rememberScoreNightLight()
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
    ScoreViewerContent(state, controller::close, controller::page, controller::transform, controller::retry, recording, recordEnabled, countdown, onRecord, nightLight, cycleNightLight)
}

@Composable
fun ScoreViewerContent(
    state: ScoreViewerState,
    onBack: () -> Unit,
    onPage: (Int) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onRetry: () -> Unit,
    recording: Boolean = false,
    recordEnabled: Boolean = true,
    countdown: Int = 0,
    onRecord: (() -> Unit)? = null,
    nightLight: ScoreNightLight = ScoreNightLight.GENTLE,
    onNightLight: () -> Unit = {},
) {
    val reading = state.reading
    val latestReading by rememberUpdatedState(reading)
    val latestState by rememberUpdatedState(state)
    val latestOnPage by rememberUpdatedState(onPage)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = (state.frame as? BitmapPageFrame)?.bitmap
    val frame = state.frame
    var retainedPage by remember(state.session) { mutableStateOf<PresentedPage?>(null) }
    val presentedPage = if (bitmap != null) PresentedPage(reading.page, bitmap)
        else if (state.loading && retainedPage?.page != reading.page) retainedPage else null
    SideEffect { retainedPage = presentedPage }
    val transition = key(state.session) { updateTransition(presentedPage, label = "PDF page transition") }
    val canNavigate = !state.loading && !transition.isRunning
    val latestCanNavigate by rememberUpdatedState(canNavigate)
    val fit = if (frame == null || viewport.width == 0 || viewport.height == 0) 0f
        else min(viewport.width.toFloat() / frame.width, viewport.height.toFloat() / frame.height)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to scores") }
                Text(state.source?.score?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onNightLight, modifier = Modifier.semantics {
                    contentDescription = "Night light: ${nightLight.label}. Switch to ${nightLight.next().label}"
                }) {
                    Icon(Icons.Default.NightsStay, contentDescription = null)
                    Text(nightLight.badge)
                }
                if (onRecord != null) {
                    if (countdown > 0) RecordingCountdownDisplay(countdown, Modifier.size(48.dp))
                    else FilledIconButton(onClick = onRecord, enabled = recordEnabled,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        if (recording) Icon(Icons.Default.Stop, contentDescription = "Stop recording")
                        else Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onError, CircleShape)
                            .semantics { contentDescription = "Start recording" })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPage(-1) }, enabled = canNavigate && state.pageCount > 0 && reading.page > 0) { Text("Previous") }
                Text(if (state.pageCount > 0) "Page ${reading.page + 1} of ${state.pageCount}" else "Opening PDF")
                Button(onClick = { onPage(1) }, enabled = canNavigate && state.pageCount > 0 && reading.page < state.pageCount - 1) { Text("Next") }
                IconButton(onClick = { onTransform(reading.zoom / 1.25f, reading.panX, reading.panY) }, enabled = state.pageCount > 0) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                }
                IconButton(onClick = { onTransform(reading.zoom * 1.25f, reading.panX, reading.panY) }, enabled = state.pageCount > 0) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                }
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
                .pointerInput(state.session, viewport) {
                    val threshold = 64.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val initial = latestState
                        var singlePointer = true
                        var displacement = androidx.compose.ui.geometry.Offset.Zero
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.id != down.id }) singlePointer = false
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer != null) displacement = pointer.position - down.position
                        } while (event.changes.any { it.pressed })
                        val current = latestState
                        if (latestCanNavigate && singlePointer && initial.frame != null && current.frame != null && !initial.loading && !current.loading &&
                            initial.reading.zoom == 1f && current.reading.zoom == 1f &&
                            initial.session == current.session && initial.reading.page == current.reading.page &&
                            abs(displacement.x) >= threshold && abs(displacement.x) > abs(displacement.y) * 1.5f) {
                            val delta = if (displacement.x < 0) 1 else -1
                            if (current.reading.page + delta in 0 until current.pageCount) latestOnPage(delta)
                        }
                    }
                }
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
                if (presentedPage != null) transition.AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it?.page },
                    transitionSpec = {
                        if (initialState == null || targetState == null) EnterTransition.None togetherWith ExitTransition.None
                        else {
                            val direction = if (targetState!!.page > initialState!!.page) 1 else -1
                            slideInHorizontally(tween(240)) { it * direction } togetherWith
                                slideOutHorizontally(tween(240)) { -it * direction }
                        }
                    },
                ) { page ->
                    if (page != null) {
                        val currentPage = page.page == reading.page && page.bitmap === bitmap
                        val pageZoom = if (currentPage) reading.zoom else 1f
                        val pageFit = if (viewport.width == 0 || viewport.height == 0) 0f else
                            min(viewport.width.toFloat() / page.bitmap.width, viewport.height.toFloat() / page.bitmap.height)
                        val pageOverflowX = max(0f, (pageFit * page.bitmap.width * pageZoom - viewport.width) / 2)
                        val pageOverflowY = max(0f, (pageFit * page.bitmap.height * pageZoom - viewport.height) / 2)
                        Image(page.bitmap.asImageBitmap(), contentDescription = "PDF page ${page.page + 1}",
                            colorFilter = nightLight.filter,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = pageZoom; scaleY = pageZoom
                                translationX = if (currentPage) reading.panX * pageOverflowX else 0f
                                translationY = if (currentPage) reading.panY * pageOverflowY else 0f
                            })
                    }
                }
            }
        }
    }
}
