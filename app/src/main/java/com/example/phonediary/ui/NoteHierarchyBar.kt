package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shows the chain from the main note down to the open note. Never truncated or collapsed. */
@Composable
fun NoteHierarchyBar(path: List<String>) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Note path", style = MaterialTheme.typography.labelSmall)
            path.forEachIndexed { index, label ->
                val isCurrent = index == path.lastIndex
                Row(modifier = Modifier.padding(start = (minOf(index, 8) * 12).dp)) {
                    if (index > 0) {
                        Text("└ ", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = if (isCurrent && path.size > 1) "$label   ← this note" else label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
