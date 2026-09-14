package com.example.phonediary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Highlights every occurrence of [query] using the theme's own
 * primaryContainer/onPrimaryContainer colors, so it stays legible in
 * both dark and colourful themes instead of a fixed yellow that
 * disappears against dark backgrounds.
 */
class HighlightTransformation(
    private val query: String,
    private val backgroundColor: Color,
    private val textColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (query.isBlank()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)
        val lowerText = text.text.lowercase()
        val lowerQuery = query.lowercase()
        var searchFrom = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQuery, searchFrom)
            if (idx < 0) break
            builder.addStyle(
                SpanStyle(background = backgroundColor, color = textColor),
                idx, idx + query.length
            )
            searchFrom = idx + query.length
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** Convenience: builds a HighlightTransformation using current theme colors. */
@Composable
fun rememberThemedHighlight(query: String): HighlightTransformation {
    val bg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    return HighlightTransformation(query, bg, fg)
}

/** Wraps [label] in a simple inline highlighted AnnotatedString for plain Text() display (search result lists). */
fun buildHighlightedString(
    text: String,
    query: String,
    highlightBg: Color,
    highlightFg: Color
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val builder = AnnotatedString.Builder()
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var cursor = 0
    while (true) {
        val idx = lowerText.indexOf(lowerQuery, cursor)
        if (idx < 0) {
            builder.append(text.substring(cursor))
            break
        }
        builder.append(text.substring(cursor, idx))
        builder.withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
            append(text.substring(idx, idx + query.length))
        }
        cursor = idx + query.length
    }
    return builder.toAnnotatedString()
}
