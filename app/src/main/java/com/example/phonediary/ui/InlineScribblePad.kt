package com.example.phonediary.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * A small drawing surface that lives directly under the note text field,
 * so the user can scribble and go right back to typing without leaving
 * the screen — unlike a full-screen drawing mode.
 */
@Composable
fun InlineScribblePad(
    onInsert: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var strokes by remember { mutableStateOf(listOf<List<Offset>>()) }
    var currentStroke by remember { mutableStateOf(listOf<Offset>()) }
    var canvasSize by remember { mutableStateOf(IntSize(800, 500)) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Scribble", style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }) { Text("↶") }
                TextButton(onClick = { strokes = emptyList() }) { Text("Clear") }
                TextButton(onClick = onCancel) { Text("✕") }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color.White)
                .border(1.dp, Color.Gray)
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> currentStroke = listOf(offset) },
                        onDrag = { change, _ ->
                            currentStroke = currentStroke + change.position
                        },
                        onDragEnd = {
                            if (currentStroke.size > 1) strokes = strokes + listOf(currentStroke)
                            currentStroke = emptyList()
                        }
                    )
                }
        ) {
            fun drawStroke(points: List<Offset>) {
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(path, color = Color.Black, style = Stroke(width = 5f))
            }
            strokes.forEach { drawStroke(it) }
            drawStroke(currentStroke)
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            fun drawToCanvas(points: List<Offset>) {
                if (points.size < 2) return
                for (i in 1 until points.size) {
                    canvas.drawLine(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, paint)
                }
            }
            strokes.forEach { drawToCanvas(it) }
            onInsert(bmp)
        }) { Text("Insert into note") }
    }
}
