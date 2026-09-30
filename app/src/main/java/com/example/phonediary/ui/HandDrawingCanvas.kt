package com.example.phonediary.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Finger-drawn handwriting/sketch surface. Draw with one finger, undo
 * the last stroke, clear everything, then Save turns the current
 * drawing into a PNG bitmap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandDrawingCanvas(
    onSave: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var strokes by remember { mutableStateOf(listOf<Path>()) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize(1000, 1400)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Draw / Handwrite") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("✕ Cancel") }
                },
                actions = {
                    TextButton(onClick = {
                        if (strokes.isNotEmpty()) strokes = strokes.dropLast(1)
                    }) { Text("↶ Undo") }
                    TextButton(onClick = { strokes = emptyList() }) { Text("Clear") }
                    TextButton(onClick = {
                        val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
                        val canvas = AndroidCanvas(bmp)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.BLACK
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 6f
                            isAntiAlias = true
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            strokeJoin = android.graphics.Paint.Join.ROUND
                        }
                        strokes.forEach { path ->
                            canvas.drawPath(path.asAndroidPath(), paint)
                        }
                        onSave(bmp)
                    }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Draw with your finger below",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val newPath = Path().apply { moveTo(offset.x, offset.y) }
                                currentPath = newPath
                            },
                            onDrag = { change, _ ->
                                currentPath?.lineTo(change.position.x, change.position.y)
                                // Force recomposition by replacing the list reference.
                                currentPath?.let { p -> strokes = strokes.dropLast(0) + emptyList() }
                            },
                            onDragEnd = {
                                currentPath?.let { strokes = strokes + it }
                                currentPath = null
                            }
                        )
                    }
            ) {
                strokes.forEach { path ->
                    drawPath(path, color = Color.Black, style = Stroke(width = 6f))
                }
                currentPath?.let { path ->
                    drawPath(path, color = Color.Black, style = Stroke(width = 6f))
                }
            }
        }
    }
}
