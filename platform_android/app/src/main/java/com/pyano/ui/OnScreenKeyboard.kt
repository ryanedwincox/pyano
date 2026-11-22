// Concern: presents globally docked piano input | Non-concern: MIDI device discovery | IO: (touches) -> live MIDI notes
package com.pyano.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pyano.keyboard.PianoKeys
import com.pyano.keyboard.PianoTouches

@Composable
fun OnScreenKeyboard(onNote: (Int, Boolean) -> Unit, onRelease: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestNote by rememberUpdatedState(onNote)
    val latestRelease by rememberUpdatedState(onRelease)
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var pressed by remember { mutableStateOf(emptySet<Int>()) }
    val touches = remember { PianoTouches { note, down ->
        pressed = if (down) pressed + note else pressed - note
        latestNote(note, down)
    } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; touches.release(); latestRelease() }
            if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); touches.release(); latestRelease() }
    }
    Surface(tonalElevation = 4.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(4.dp)) {
            val octaves = PianoKeys.octavesForWidth(maxWidth.value)
            val keys = remember(octaves) { PianoKeys(octaves) }
            val whiteWidth = maxWidth / keys.whiteNotes.size
            val height = if (maxHeight < 400.dp) 112.dp else 144.dp
            Box(Modifier.testTag("on-screen-keyboard").fillMaxWidth().height(height)
                .pointerInput(keys, foreground) {
                    try {
                        if (foreground) awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                touches.update(event.changes.filter { it.pressed }.mapNotNull {
                                    keys.noteAt(it.position.x, it.position.y, size.width.toFloat(), size.height.toFloat())
                                })
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } finally { touches.release() }
                }) {
                fun keyModifier(note: Int) = Modifier.semantics {
                    contentDescription = PianoKeys.label(note)
                    role = Role.Button
                    onClick { if (foreground && note !in pressed) { latestNote(note, true); latestNote(note, false) }; true }
                }
                keys.whiteNotes.forEachIndexed { index, note ->
                    Box(Modifier.absoluteOffset(x = whiteWidth * index).width(whiteWidth).fillMaxHeight().then(keyModifier(note))
                        .background(if (note in pressed) MaterialTheme.colorScheme.primaryContainer else Color.White)
                        .border(1.dp, Color.DarkGray), contentAlignment = Alignment.BottomCenter) {
                        if (note % 12 == 0) Text(PianoKeys.label(note), color = Color.DarkGray, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                keys.blackNotes.forEach { note ->
                    Box(Modifier.absoluteOffset(x = whiteWidth * keys.blackPosition(note)).width(whiteWidth * .62f).fillMaxHeight(.62f).then(keyModifier(note))
                        .background(if (note in pressed) MaterialTheme.colorScheme.primary else Color.Black))
                }
            }
        }
    }
}
