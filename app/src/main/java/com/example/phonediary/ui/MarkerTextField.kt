package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
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

/** Matches 📎[image: …], 🔗[file: …], ✍[drawing: …], 🗒[note: … #id] and 🕒[time: …]. */
val markerRegex = Regex("""[📎🔗✍🗒🕒]\[(image|file|drawing|note|time):\s*([^\]]+)\]""")

private data class MarkerHit(val type: String, val name: String, val endExclusive: Int)

/** A marker is "hit" only when the cursor lands strictly inside it, so tapping just after one lets you keep typing. */
private fun findMarkerAt(text: String, charOffset: Int): MarkerHit? {
    for (match in markerRegex.findAll(text)) {
        val start = match.range.first
        val endExclusive = match.range.last + 1
        if (charOffset > start && charOffset < endExclusive) {
            return MarkerHit(match.groupValues[1], match.groupValues[2], endExclusive)
        }
    }
    return null
}

/**
 * Plain-text editor where markers render underlined/colored. Tapping inside
 * one reports it through onMarkerClick and moves the cursor just past it.
 * Border, padding and scrolling come from ScrollableNoteBox.
 */
@Composable
fun MarkerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "",
    minLines: Int = 1,
    softWrap: Boolean = true,
    onMarkerClick: (type: String, name: String) -> Unit
) {
    val markerColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
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
            val hit = findMarkerAt(newValue.text, newValue.selection.start)
            if (hit != null) {
                onMarkerClick(hit.type, hit.name)
                onValueChange(newValue.copy(selection = TextRange(hit.endExclusive.coerceAtMost(newValue.text.length))))
                return
            }
        }
        onValueChange(newValue)
    }

    // propagateMinConstraints makes the field fill the minimum width it is given,
    // so tapping blank space to the right of short text still focuses it.
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (value.text.isEmpty()) {
            Text(placeholderText, color = placeholderColor, style = LocalTextStyle.current)
        }
        /*BasicTextField(
            value = value,
            onValueChange = ::handleValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            softWrap = softWrap,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            visualTransformation = markerTransformation,
            cursorBrush = SolidColor(markerColor)
        )*/
        BasicTextField(
            value = value,
            onValueChange = ::handleValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            visualTransformation = markerTransformation,
            cursorBrush = SolidColor(markerColor)
        )
    }
}
