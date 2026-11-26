// Concern: warms score pages and remembers reading comfort | Non-concern: PDF storage | IO: (preference, taps) -> display-only color filter
package com.pyano.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext

enum class ScoreNightLight(val label: String, val badge: String, red: Float, green: Float, blue: Float) {
    OFF("Off", "Off", 1f, 1f, 1f),
    GENTLE("Gentle", "1", .89f, .84f, .75f),
    DIM("Dim", "2", .74f, .685f, .615f);

    val filter: ColorFilter? = if (this.name == "OFF") null else ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        red, 0f, 0f, 0f, 0f,
        0f, green, 0f, 0f, 0f,
        0f, 0f, blue, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    fun next() = entries[(ordinal + 1) % entries.size]
}

@Composable
internal fun rememberScoreNightLight(): Pair<ScoreNightLight, () -> Unit> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("score-reading", Context.MODE_PRIVATE) }
    val selected = remember(preferences) {
        mutableStateOf(ScoreNightLight.entries.find { it.name == preferences.getString("night-light", null) } ?: ScoreNightLight.GENTLE)
    }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "night-light") selected.value = ScoreNightLight.entries.find {
                it.name == preferences.getString(key, null)
            } ?: ScoreNightLight.GENTLE
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return selected.value to {
        val next = selected.value.next()
        preferences.edit().putString("night-light", next.name).apply()
        selected.value = next
    }
}
