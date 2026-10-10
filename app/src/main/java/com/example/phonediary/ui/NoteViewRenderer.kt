package com.example.phonediary.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.phonediary.files.SubNoteManager

/**
 * Read-only rendering of note text: markers show as an icon plus readable
 * label (never raw brackets) and are tappable. onMarkerClick receives the
 * marker type ("image", "file", "drawing", "note") and its raw label.
 */
@Composable
fun NoteViewRenderer(
    text: String,
    modifier: Modifier = Modifier,
    onMarkerClick: (type: String, name: String) -> Unit
) {
    val markerColor = MaterialTheme.colorScheme.primary

    val annotated = remember(text, markerColor) {
        val builder = AnnotatedString.Builder()
        var cursor = 0
        markerRegex.findAll(text).forEach { match ->
            builder.append(text.substring(cursor, match.range.first))
            val type = match.groupValues[1]
            val label = match.groupValues[2]
            val icon = when (type) {
                "image" -> "🖼"
                "drawing" -> "✍"
                SubNoteManager.TYPE -> "🗒"
                else -> "🔗"
            }
            val shown = if (type == SubNoteManager.TYPE) SubNoteManager.displayLabel(label) else label
            builder.pushStringAnnotation("marker", "$type|$label")
            builder.withStyle(SpanStyle(color = markerColor, textDecoration = TextDecoration.Underline)) {
                append("$icon $shown")
            }
            builder.pop()
            cursor = match.range.last + 1
        }
        builder.append(text.substring(cursor))
        builder.toAnnotatedString()
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations("marker", offset, offset).firstOrNull()?.let { annotation ->
                val parts = annotation.item.split("|", limit = 2)
                if (parts.size == 2) onMarkerClick(parts[0], parts[1])
            }
        }
    )

    if (text.isBlank()) {
        Text(
            "(empty note)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
