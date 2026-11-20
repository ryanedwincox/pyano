// Concern: verifies private score browsing controls | Non-concern: live cloud downloads | IO: (library state) -> UI assertions
package com.pyano

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
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

    @Test fun offlineRowsOfferOnlyLocalRemovalAndRetainVersionStatus() {
        var removed: Score? = null
        compose.setContent {
            MaterialTheme {
                ScoreLibraryContent(
                    ScoreLibraryState("namespace_a", "https://music.example.com", "owner_a", listOf(ScoreRow(score, true, "Available offline · older version 1"))),
                    {}, {}, { removed = it }, {}
                )
            }
        }
        compose.onNodeWithText("Offline nocturne").assertIsDisplayed()
        compose.onNodeWithText("Available offline · older version 1").assertIsDisplayed()
        compose.onNodeWithText("Download PDF").assertDoesNotExist()
        compose.onNodeWithText("Remove local copy").performClick()
        compose.runOnIdle { assertEquals(score, removed) }
    }

    @Test fun profileStateClearingRemovesPriorOwnerRowsAndOffersProvisioningGuidance() {
        val state = mutableStateOf(ScoreLibraryState("namespace_a", "https://music.example.com", "owner_a", listOf(ScoreRow(score, true, "Available offline"))))
        compose.setContent { MaterialTheme { ScoreLibraryContent(state.value, {}, {}, {}, {}) } }
        compose.onNodeWithText("Offline nocturne").assertIsDisplayed()
        compose.runOnIdle { state.value = ScoreLibraryState() }
        compose.onNodeWithText("Offline nocturne").assertDoesNotExist()
        compose.onNodeWithText("Choose a Cloud profile in Recorder settings to browse private scores.").assertIsDisplayed()
        compose.onNodeWithText("Refresh scores").assertDoesNotExist()
    }

    @Test fun scopeFailureGuidanceAndCancellationRemainActionable() {
        var canceled = 0
        compose.setContent {
            MaterialTheme {
                ScoreLibraryContent(
                    ScoreLibraryState("namespace_a", "https://music.example.com", "owner_a", busy = true,
                        message = "Provision a new Cloud device token with scores:read permission in Recorder settings."),
                    {}, {}, {}, { canceled++ }
                )
            }
        }
        compose.onNodeWithText("Refresh scores").assertIsNotEnabled()
        compose.onNodeWithText("Provision a new Cloud device token with scores:read permission in Recorder settings.").assertIsDisplayed()
        compose.onNodeWithText("Cancel operation").performClick()
        compose.runOnIdle { assertEquals(1, canceled) }
    }
}
