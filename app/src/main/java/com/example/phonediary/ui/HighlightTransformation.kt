package com.example.phonediary.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Highlights every occurrence of [query] inside the text with a yellow background. */
class HighlightTransformation(private val query: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (query.isBlank()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)
        val lowerText = text.text.lowercase()
        val lowerQuery = query.lowercase()
        var searchFrom = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQuery, searchFrom)
            if (idx < 0) break
            builder.addStyle(SpanStyle(background = Color(0xFFFFF176)), idx, idx + query.length)
            searchFrom = idx + query.length
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
