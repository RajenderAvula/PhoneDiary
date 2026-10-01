package com.example.phonediary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val markerRegex = Regex("""[📎🔗✍]\[(image|file|drawing):\s*([^\]]+)\]""")

private data class MarkerHit(val type: String, val name: String, val endExclusive: Int)

/** If [charOffset] falls inside any marker's character range, returns that marker. */
private fun findMarkerAt(text: String, charOffset: Int): MarkerHit? {
    for (match in markerRegex.findAll(text)) {
        val start = match.range.first
        val endExclusive = match.range.last + 1
        if (charOffset in start..endExclusive) {
            return MarkerHit(match.groupValues[1], match.groupValues[2], endExclusive)
        }
    }
    return null
}

/**
 * A plain-text editor where any 📎[image: ...], 🔗[file: ...], or
 * ✍[drawing: ...] marker renders underlined/colored. Tapping one places
 * the cursor inside it as normal text-field behavior always does — this
 * is detected via onValueChange (the cursor lands inside the marker's
 * range), which opens the attachment and then moves the cursor just
 * past the marker so normal typing resumes immediately after it.
 */
@Composable
fun MarkerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "",
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    onMarkerClick: (type: String, name: String) -> Unit
) {
    val markerColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outline
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    val markerTransformation = remember(markerColor) {
        VisualTransformation { text ->
            val builder = AnnotatedString.Builder(text.text)
            markerRegex.findAll(text.text).forEach { m ->
                builder.addStyle(
                    SpanStyle(color = markerColor, textDecoration = TextDecoration.Underline),
                    m.range.first, m.range.last + 1
                )
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }

    fun handleValueChange(newValue: TextFieldValue) {
        if (newValue.selection.collapsed && newValue.text == value.text) {
            // Text didn't change — this was a pure cursor move (tap or arrow key).
            val hit = findMarkerAt(newValue.text, newValue.selection.start)
            if (hit != null) {
                onMarkerClick(hit.type, hit.name)
                // Move the cursor just past the marker so the user can keep typing
                // right after it, rather than leaving the cursor stuck inside it.
                val safeOffset = hit.endExclusive.coerceAtMost(newValue.text.length)
                onValueChange(newValue.copy(selection = TextRange(safeOffset)))
                return
            }
        }
        onValueChange(newValue)
    }

    Box(
        modifier = modifier
            .border(1.dp, outlineColor, RoundedCornerShape(4.dp))
            .padding(12.dp)
    ) {
        if (value.text.isEmpty()) {
            Text(placeholderText, color = placeholderColor, style = LocalTextStyle.current)
        }
        BasicTextField(
            value = value,
            onValueChange = ::handleValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            visualTransformation = markerTransformation,
            cursorBrush = SolidColor(markerColor)
        )
    }
}
