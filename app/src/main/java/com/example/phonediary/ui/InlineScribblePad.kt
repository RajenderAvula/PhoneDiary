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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private enum class DrawTool { PEN, ERASER }

private data class StrokeData(val points: List<Offset>, val tool: DrawTool)

/**
 * Full-screen drawing/scribble surface with Pen and Eraser tools.
 * Eraser removes points under the finger from existing strokes (not
 * just drawing white over them), so it works correctly regardless of
 * stroke overlap order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineScribblePad(
    onInsert: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var strokes by remember { mutableStateOf(listOf<StrokeData>()) }
    var currentPoints by remember { mutableStateOf(listOf<Offset>()) }
    var tool by remember { mutableStateOf(DrawTool.PEN) }
    var canvasSize by remember { mutableStateOf(IntSize(1000, 1400)) }

    val eraserRadius = 24f

    fun eraseAt(point: Offset) {
        strokes = strokes.mapNotNull { stroke ->
            if (stroke.tool == DrawTool.ERASER) return@mapNotNull stroke
            val remaining = stroke.points.filter { p ->
                val dx = p.x - point.x
                val dy = p.y - point.y
                (dx * dx + dy * dy) > eraserRadius * eraserRadius
            }
            if (remaining.isEmpty()) null else stroke.copy(points = remaining)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scribble") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("✕ Cancel") }
                },
                actions = {
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }) { Text("↶ Undo") }
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
                        strokes.forEach { stroke ->
                            val pts = stroke.points
                            for (i in 1 until pts.size) {
                                canvas.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, paint)
                            }
                        }
                        onInsert(bmp)
                    }) { Text("Insert") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tool == DrawTool.PEN,
                    onClick = { tool = DrawTool.PEN },
                    label = { Text("✏ Pen") }
                )
                FilterChip(
                    selected = tool == DrawTool.ERASER,
                    onClick = { tool = DrawTool.ERASER },
                    label = { Text("🧹 Eraser") }
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White)
                    .border(1.dp, Color.Gray)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(tool) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (tool == DrawTool.PEN) {
                                    currentPoints = listOf(offset)
                                } else {
                                    eraseAt(offset)
                                }
                            },
                            onDrag = { change, _ ->
                                if (tool == DrawTool.PEN) {
                                    currentPoints = currentPoints + change.position
                                } else {
                                    eraseAt(change.position)
                                }
                            },
                            onDragEnd = {
                                if (tool == DrawTool.PEN && currentPoints.size > 1) {
                                    strokes = strokes + StrokeData(currentPoints, DrawTool.PEN)
                                }
                                currentPoints = emptyList()
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
                strokes.forEach { drawStroke(it.points) }
                if (tool == DrawTool.PEN) drawStroke(currentPoints)
            }

            Text(
                if (tool == DrawTool.ERASER) "Drag over strokes to erase" else "Draw with your finger",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
