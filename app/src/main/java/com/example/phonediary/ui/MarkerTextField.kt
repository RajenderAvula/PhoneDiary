package com.example.phonediary.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val markerRegex = Regex("""[📎🔗✍]\[(image|file|drawing):\s*([^\]]+)\]""")

private fun findMarkerAt(text: String, charOffset: Int): Pair<String, String>? {
    for (match in markerRegex.findAll(text)) {
        if (charOffset in match.range.first..(match.range.last + 1)) {
            return match.groupValues[1] to match.groupValues[2]
        }
    }
    return null
}

/**
 * A plain-text editor where any 📎[image: ...], 🔗[file: ...], or
 * ✍[drawing: ...] marker renders underlined/colored and is tappable —
 * tapping one opens its attachment instead of placing the cursor there.
 * Everything else behaves like a normal multi-line text field.
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
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
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

    Box(
        modifier = modifier
            .border(1.dp, outlineColor, RoundedCornerShape(4.dp))
            .padding(12.dp)
            .pointerInput(value.text, layoutResult) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val layout = layoutResult
                    if (layout != null) {
                        val offset = layout.getOffsetForPosition(down.position)
                        val marker = findMarkerAt(value.text, offset)
                        if (marker != null) {
                            down.consume()
                            onMarkerClick(marker.first, marker.second)
                        }
                    }
                }
            }
    ) {
        if (value.text.isEmpty()) {
            Text(placeholderText, color = placeholderColor, style = LocalTextStyle.current)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            visualTransformation = markerTransformation,
            onTextLayout = { layoutResult = it },
            cursorBrush = SolidColor(markerColor)
        )
    }
}
