// Concern: verifies private score browsing controls | Non-concern: live cloud downloads | IO: (library state) -> UI assertions
package com.pyano

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pyano.scores.*
import com.pyano.ui.ScoreLibraryContent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScoreLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val score = Score("fixture_score", "Offline nocturne", "1", 5, "a".repeat(64))

    @Before fun showSyntheticUiAboveKeyguard() {
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    @Test fun localRemovalRequiresConfirmationAndHidesDebugMetadata() {
        var removed: Score? = null
        compose.setContent {
            MaterialTheme {
                ScoreLibraryContent(
                    ScoreLibraryState("namespace_a", "https://music.example.com", "owner_a", listOf(ScoreRow(score, true, "Available offline · older version 1"))),
                    {}, { removed = it }, {}
                )
            }
        }
        compose.onNodeWithText("Offline nocturne").assertIsDisplayed()
        compose.onNodeWithText("Available offline · older version 1").assertDoesNotExist()
        compose.onNodeWithText("https://music.example.com", substring = true).assertDoesNotExist()
        compose.onNodeWithText("owner_a", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Download PDF").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove local copy of Offline nocturne").performClick()
        compose.runOnIdle { assertEquals(null, removed) }
        compose.onNodeWithText("Cloud is not changed.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Remove local copy of Offline nocturne").performClick()
        compose.onNodeWithText("Remove local copy").performClick()
        compose.runOnIdle { assertEquals(score, removed) }
    }

    @Test fun revisedScoreAndRetainedCopyHaveDistinctClickableNames() {
        val current = score.copy(version = "2", sha256 = "b".repeat(64))
        var opened: Score? = null
        compose.setContent {
            MaterialTheme {
                ScoreLibraryContent(ScoreLibraryState(namespace = "namespace_a", rows = listOf(
                    ScoreRow(current, false, "", false, true), ScoreRow(score, true, "", true, false)
                )), {}, {}, {}, { opened = it })
            }
        }
        compose.onNodeWithText("Offline nocturne (saved copy)").performClick()
        compose.runOnIdle { assertEquals(score, opened) }
        compose.onNodeWithText("Offline nocturne").performClick()
        compose.runOnIdle { assertEquals(current, opened) }
    }

    @Test fun openingSettingsCancelsPendingPdfOpenBeforeEditing() {
        var cancelled = false
        var settingsOpened = false
        compose.setContent {
            MaterialTheme { ScoreLibraryContent(ScoreLibraryState(namespace = "namespace_a", busy = true), {}, {},
                { cancelled = true }, onServerSettings = { assertEquals(true, cancelled); settingsOpened = true }) }
        }
        compose.onNodeWithContentDescription("Server settings").performClick()
        compose.runOnIdle { assertEquals(true, settingsOpened) }
    }

    @Test fun unavailableOfflineCopyShowsRecoveryGuidance() {
        compose.setContent {
            MaterialTheme { ScoreLibraryContent(ScoreLibraryState(namespace = "namespace_a", rows = listOf(
                ScoreRow(score, false, "Local copy missing or corrupt; download again.", true, false)
            )), {}, {}, {}) }
        }
        compose.onNodeWithText("Local copy unavailable and no longer in Cloud.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove local copy of Offline nocturne").assertIsEnabled()
    }

    @Test fun profileStateClearingRemovesPriorOwnerRowsAndOffersProvisioningGuidance() {
        val state = mutableStateOf(ScoreLibraryState("namespace_a", "https://music.example.com", "owner_a", listOf(ScoreRow(score, true, "Available offline"))))
        compose.setContent { MaterialTheme { ScoreLibraryContent(state.value, {}, {}, {}) } }
        compose.onNodeWithText("Offline nocturne").assertIsDisplayed()
        compose.runOnIdle { state.value = ScoreLibraryState() }
        compose.onNodeWithText("Offline nocturne").assertDoesNotExist()
        compose.onNodeWithText("Choose a Cloud profile in Server settings to browse private scores.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh scores").assertIsNotEnabled()
    }

    @Test fun refreshUsesInlineSpinnerAndKeepsFailuresActionableAfterCompletion() {
        var canceled = 0
        val message = "Provision a new Cloud device token with scores:read permission in Server settings."
        val state = mutableStateOf(ScoreLibraryState(namespace = "namespace_a", busy = true, message = message))
        compose.setContent { MaterialTheme { ScoreLibraryContent(state.value, {}, {}, { canceled++ }) } }
        compose.onNodeWithContentDescription("Refresh scores").assertDoesNotExist()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Cancel operation").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true).assert(hasAnyAncestor(hasContentDescription("Cancel operation")))
        compose.onNodeWithContentDescription("Cancel operation").performClick()
        compose.runOnIdle { assertEquals(1, canceled) }
        compose.onNodeWithContentDescription("Canceling operation").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh scores").assertIsEnabled()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }
    @Test fun titlesOpenCloudOnlyScoresAndProfileChangesDismissRemoval() {
        var opened: Score? = null
        var removed: Score? = null
        var refreshes = 0
        val state = mutableStateOf(ScoreLibraryState("namespace_a", rows = listOf(ScoreRow(score, false, "Cloud only · version 1"))))
        compose.setContent {
            MaterialTheme { ScoreLibraryContent(state.value, { refreshes++ }, { removed = it }, {}, { opened = it }) }
        }
        compose.onNodeWithText(score.title).performClick()
        compose.runOnIdle { assertEquals(score, opened) }
        compose.onNodeWithContentDescription("Refresh scores").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state.value = state.value.copy(rows = listOf(ScoreRow(score, true, "Available offline")))
        }
        compose.onNodeWithContentDescription("Remove local copy of Offline nocturne").performClick()
        compose.runOnIdle { state.value = ScoreLibraryState("namespace_b") }
        compose.onNodeWithText("Remove local copy?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, removed) }
    }

}
