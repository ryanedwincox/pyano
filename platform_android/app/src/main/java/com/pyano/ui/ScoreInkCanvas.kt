// Concern: maps pen gestures to stable PDF coordinates | Non-concern: persistence | IO: (page geometry, touch) -> normalized strokes
package com.pyano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import com.pyano.markup.*

@Composable
internal fun ScoreInkCanvas(
    strokes: List<InkStroke>, enabled: Boolean, pageWidth: Float, pageHeight: Float, zoom: Float,
    translationX: Float, translationY: Float, modifier: Modifier, onStroke: (List<List<Float>>) -> Unit,
) {
    var draft by remember { mutableStateOf<List<List<Float>>>(emptyList()) }
    val latestSubmit by rememberUpdatedState(onStroke)
    Canvas(modifier.pointerInput(enabled, pageWidth, pageHeight, zoom, translationX, translationY) {
        if (!enabled || pageWidth <= 0f || pageHeight <= 0f) return@pointerInput
        fun point(position: Offset): List<Float>? {
            val x = (position.x - size.width / 2f - translationX) / (pageWidth * zoom) + .5f
            val y = (position.y - size.height / 2f - translationY) / (pageHeight * zoom) + .5f
            return if (x in 0f..1f && y in 0f..1f) listOf(x, y) else null
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val first = point(down.position)
            var valid = first != null
            draft = first?.let { listOf(it) } ?: emptyList()
            down.consume()
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.id != down.id }) valid = false
                val change = event.changes.firstOrNull { it.id == down.id }
                if (valid && change != null) {
                    point(change.position)?.let { next ->
                        if (draft.lastOrNull() != next) {
                            val previous = if (draft.size >= 256) draft.filterIndexed { index, _ -> index % 2 == 0 } else draft
                            draft = previous + listOf(next)
                        }
                    }
                }
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (valid && draft.isNotEmpty()) latestSubmit(draft)
            draft = emptyList()
        }
    }) {
        fun draw(points: List<List<Float>>) {
            if (points.isEmpty()) return
            fun mapped(p: List<Float>) = Offset(size.width / 2 + (p[0] - .5f) * pageWidth * zoom + translationX,
                size.height / 2 + (p[1] - .5f) * pageHeight * zoom + translationY)
            val width = pageWidth * zoom * INK_WIDTH
            if (points.size == 1) drawCircle(Color(INK_COLOR), width / 2, mapped(points[0]))
            else {
                val path = Path()
                points.forEachIndexed { index, p -> val location = mapped(p)
                    if (index == 0) path.moveTo(location.x, location.y) else path.lineTo(location.x, location.y) }
                drawPath(path, Color(INK_COLOR), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        strokes.filter { !it.deleted }.forEach { draw(it.points) }
        draw(draft)
    }
}
