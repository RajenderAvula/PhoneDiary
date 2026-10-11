package com.example.phonediary.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Remembers whether the note box wraps long lines (default) or scrolls sideways. */
object NoteBoxPrefs {
    private const val PREFS = "phone_diary_notebox"
    private const val KEY_WRAP = "wrap_lines"

    fun wrapLines(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WRAP, true)

    fun setWrapLines(context: Context, wrap: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WRAP, wrap).apply()
    }
}

/**
 * A bordered box with a maximum height. Taller content scrolls vertically.
 * With wrapLines = false it also scrolls sideways. Thin scroll bars show position.
 * content receives the minimum width it should fill.
 */
@Composable
fun ScrollableNoteBox(
    wrapLines: Boolean,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 360.dp,
    content: @Composable (minContentWidth: Dp) -> Unit
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val barColor = MaterialTheme.colorScheme.outline
    val borderColor = MaterialTheme.colorScheme.outline

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .drawWithContent {
                drawContent()
                val vMax = vScroll.maxValue
                if (vMax in 1 until Int.MAX_VALUE) {
                    val viewport = size.height
                    val barH = (viewport * viewport / (viewport + vMax)).coerceAtLeast(24.dp.toPx())
                    val barY = (viewport - barH) * (vScroll.value.toFloat() / vMax)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(size.width - 5.dp.toPx(), barY),
                        size = Size(3.dp.toPx(), barH),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
                val hMax = hScroll.maxValue
                if (!wrapLines && hMax in 1 until Int.MAX_VALUE) {
                    val viewport = size.width
                    val barW = (viewport * viewport / (viewport + hMax)).coerceAtLeast(24.dp.toPx())
                    val barX = (viewport - barW) * (hScroll.value.toFloat() / hMax)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(barX, size.height - 5.dp.toPx()),
                        size = Size(barW, 3.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
    ) {
        val contentMin = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .verticalScroll(vScroll)
                .then(if (wrapLines) Modifier else Modifier.horizontalScroll(hScroll))
                .padding(12.dp)
        ) {
            content(contentMin)
        }
    }
}
