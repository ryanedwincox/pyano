// Concern: verifies shared mastering tabs and silent analysis/export | Non-concern: personal recordings | IO: (synthetic WAV, UI) -> assertions
package com.pyano

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pyano.audio.*
import com.pyano.ui.MasteringControls
import com.pyano.ui.MasteringScreen
import com.pyano.ui.MasteringOverview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MasteringUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showAboveLockScreen() {
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 27) { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
        }
    }
    @Test fun defaultTabPresetsAndAdvancedEditsShareOneSettingSet() {
        val settings = mutableStateOf(MasteringSettings())
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MasteringControls(settings.value, { settings.value = it }, true, null, -1f, null)
            }
        } }
        compose.onNodeWithText("Simple").assertIsSelected()
        compose.onNodeWithText("Balanced", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ratio").assertDoesNotExist()
        compose.onNodeWithText("Gentle").performClick()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithContentDescription("Ratio").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1.25f, 1f..20f)))
        compose.onNodeWithContentDescription("Ratio").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(3f, settings.value.compression.ratio, .01f); assertNull(settings.value.preset) }
        compose.onNodeWithText("Simple").performScrollTo().performClick()
        compose.onNodeWithText("Custom compression", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Even").performClick()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithContentDescription("Ratio").performScrollTo()
        compose.runOnIdle { assertEquals(1.8f, settings.value.compression.ratio, .01f) }
        compose.onNodeWithText("Simple").performScrollTo().performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.runOnIdle { assertEquals(MasteringCompression(), settings.value.compression) }
    }

    @Test fun graphTapSeeksToTheChosenPassage() {
        val measurement = MasteringMeasurement(-18f, -3f, listOf(MasteringPoint(1f, -18f, 2f, 1f)))
        val plan = MasteringPlan(MasteringSettings(), 48000, 0f, 0f, measurement, measurement, "synthetic", 60000)
        var fraction = -1f
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { MasteringOverview(plan) { fraction = it } }
        } }
        compose.onNodeWithContentDescription("Loudness throughout the recording").performScrollTo()
            .performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(.5f, fraction, .02f) }
        compose.onNodeWithContentDescription("Compressor and limiter gain reduction throughout the recording").performScrollTo()
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .75f, height * .5f)) }
        compose.runOnIdle { assertEquals(.75f, fraction, .02f) }
    }

    @Test fun silentlyAnalyzesAndExportsASeparateConsistentCopy() {
        val root = File(compose.activity.cacheDir, "mastering-ui-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "synthetic.wav")
        val frames = 48000 * 2
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { i ->
            val sample = (.035 * sin(2 * PI * 1000 * i / 48000) * 32767).toInt().toShort()
            bytes.putShort(sample).putShort(sample)
        }
        file.writeBytes(bytes.array())
        val active = mutableStateOf(true)
        var completed = false
        val output = File(root, "synthetic_final.wav")
        try {
            compose.setContent { MaterialTheme { if (active.value) MasteringScreen(
                RecordingInfo("Synthetic mastering", file.path, 2000, file.lastModified(), file.length()), {}, { completed = true }) } }
            compose.waitUntil(30000) { compose.onAllNodesWithText("Whole recording").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Loudness throughout the recording").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Compressor and limiter gain reduction throughout the recording").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Export Master").performScrollTo().performClick()
            compose.waitUntil(30000) { completed }
            assertTrue(output.isFile)
            assertArrayEquals(bytes.array(), file.readBytes())
            assertEquals(file.length(), output.length())
            runBlocking {
                val measured = measureMastering(output, null)
                assertEquals(-18f, measured.lufs!!, .3f)
                assertTrue(measured.peakDb <= -.99f)
            }
        } finally {
            compose.runOnIdle { active.value = false }
            compose.waitForIdle()
            root.deleteRecursively()
        }
    }
}
