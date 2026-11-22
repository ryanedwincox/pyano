// Concern: verifies touch piano input and lifecycle cleanup | Non-concern: native audio | IO: (touch gestures) -> balanced MIDI events
package com.pyano

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.pyano.ui.OnScreenKeyboard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OnScreenKeyboardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun wakeTestWindow() {
        compose.activity.runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    @Test fun rtlLayoutKeepsDisplayedKeysAlignedWithTouchNotes() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                MaterialTheme { Box(Modifier.width(500.dp).height(300.dp)) { OnScreenKeyboard({ note, on -> events.add(note to on) }, {}) } }
            }
        }
        val first = compose.onNodeWithContentDescription("C4").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription("D4").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < next.left)
        compose.onNodeWithTag("on-screen-keyboard").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .02f, height * .9f))
        }
        compose.runOnIdle { assertEquals(listOf(60 to true, 60 to false), events) }
    }

    @Test fun chordGlissandoAndRemovalReleaseEveryNote() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var visible by mutableStateOf(true)
        var releases = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(800.dp).height(500.dp)) {
                    if (visible) OnScreenKeyboard({ note, on -> events.add(note to on) }, { releases++ })
                }
            }
        }
        val keyboard = compose.onNodeWithTag("on-screen-keyboard")
        assertTrue(compose.onNodeWithContentDescription("C4").fetchSemanticsNode().boundsInRoot.left <
            compose.onNodeWithContentDescription("B4").fetchSemanticsNode().boundsInRoot.left)
        keyboard.performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(width * .02f, height * .9f))
            down(1, androidx.compose.ui.geometry.Offset(width * .18f, height * .9f))
            moveTo(0, androidx.compose.ui.geometry.Offset(width * .09f, height * .9f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            val on = events.filter { it.second }.map { it.first }.sorted()
            val off = events.filter { !it.second }.map { it.first }.sorted()
            assertTrue(on.size >= 3)
            assertEquals(on, off)
        }
        compose.onNodeWithContentDescription("C4").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(60 to true, 60 to false), events.takeLast(2)); visible = false }
        compose.runOnIdle { assertTrue(releases > 0) }
    }
}
