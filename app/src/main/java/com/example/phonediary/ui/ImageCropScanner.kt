package com.example.phonediary.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.phonediary.files.TextScanHelper
import kotlinx.coroutines.launch
import kotlin.math.min

private const val TL = 0
private const val TR = 1
private const val BR = 2
private const val BL = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScanner(
    imageUri: Uri,
    onExtractedText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    var corners by remember { mutableStateOf(listOf(Offset.Zero, Offset.Zero, Offset.Zero, Offset.Zero)) }

    // Custom rotation, applied to the SOURCE bitmap before cropping — a precise
    // text field (0.1 to 360 degrees), not just 90-degree quick-rotate buttons.
    var rotationInput by remember { mutableStateOf("0") }
    var appliedRotationDegrees by remember { mutableStateOf(0f) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var rotationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imageUri) {
        sourceBitmap = TextScanHelper.loadBitmap(context, imageUri)
    }

    // Bitmap actually displayed/cropped — original bitmap rotated by the applied angle.
    val displayBitmap = remember(sourceBitmap, appliedRotationDegrees) {
        val bmp = sourceBitmap ?: return@remember null
        if (appliedRotationDegrees == 0f) bmp
        else rotateBitmap(bmp, appliedRotationDegrees)
    }

    val fit = remember(displayBitmap, containerSize) {
        val bmp = displayBitmap
        if (bmp == null || containerSize.width == 0 || containerSize.height == 0) null
        else {
            val fitScale = min(
                containerSize.width.toFloat() / bmp.width,
                containerSize.height.toFloat() / bmp.height
            )
            val drawnW = bmp.width * fitScale
            val drawnH = bmp.height * fitScale
            val offX = (containerSize.width - drawnW) / 2f
            val offY = (containerSize.height - drawnH) / 2f
            FitInfo(fitScale, offX, offY, drawnW, drawnH)
        }
    }

    // Re-center default corners whenever the fit geometry changes (including after rotation).
    LaunchedEffect(fit) {
        val f = fit ?: return@LaunchedEffect
        val insetX = f.drawnW * 0.1f
        val insetY = f.drawnH * 0.1f
        corners = listOf(
            Offset(f.offsetX + insetX, f.offsetY + insetY),
            Offset(f.offsetX + f.drawnW - insetX, f.offsetY + insetY),
            Offset(f.offsetX + f.drawnW - insetX, f.offsetY + f.drawnH - insetY),
            Offset(f.offsetX + insetX, f.offsetY + f.drawnH - insetY)
        )
        previewBitmap = null
    }

    fun applyRotation() {
        val degrees = rotationInput.toFloatOrNull()
        if (degrees == null || degrees < 0.1f || degrees > 360f) {
            rotationError = "Enter an angle between 0.1 and 360"
            return
        }
        rotationError = null
        appliedRotationDegrees = degrees
    }

    fun buildWarpedBitmap(): Bitmap? {
        val bmp = displayBitmap ?: return null
        val f = fit ?: return null

        fun toBitmapSpace(p: Offset) = Offset(
            (p.x - f.offsetX) / f.fitScale,
            (p.y - f.offsetY) / f.fitScale
        )
        val src = corners.map { toBitmapSpace(it) }

        val topLen = distance(src[TL], src[TR])
        val bottomLen = distance(src[BL], src[BR])
        val leftLen = distance(src[TL], src[BL])
        val rightLen = distance(src[TR], src[BR])

        val outW = ((topLen + bottomLen) / 2f).toInt().coerceIn(50, 2000)
        val outH = ((leftLen + rightLen) / 2f).toInt().coerceIn(50, 2000)

        val srcPoints = floatArrayOf(
            src[TL].x, src[TL].y,
            src[TR].x, src[TR].y,
            src[BR].x, src[BR].y,
            src[BL].x, src[BL].y
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )

        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
        if (!ok) return null

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(output)
        canvas.drawBitmap(bmp, matrix, null)
        return output
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust borders") },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("✕ Cancel") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text(
                "Drag each corner to fit the text region. Pinch to zoom for precision.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // ---- Custom rotation control ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = rotationInput,
                    onValueChange = { input ->
                        rotationInput = input.filter { it.isDigit() || it == '.' }
                    },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    label = { Text("Angle °") },
                    placeholder = { Text("0.1–360") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { applyRotation() }) { Text("Rotate") }
                Spacer(Modifier.width(8.dp))
                if (appliedRotationDegrees != 0f) {
                    TextButton(onClick = {
                        rotationInput = "0"
                        appliedRotationDegrees = 0f
                    }) { Text("Reset") }
                }
            }
            rotationError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (appliedRotationDegrees != 0f) {
                Text(
                    "Applied: ${appliedRotationDegrees}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .onSizeChanged { containerSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panDelta, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            pan += panDelta
                        }
                    }
            ) {
                val bmp = displayBitmap
                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = pan.x,
                                translationY = pan.y
                            )
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val fullRect = Path().apply {
                                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                            }
                            val quad = Path().apply {
                                moveTo(corners[TL].x, corners[TL].y)
                                lineTo(corners[TR].x, corners[TR].y)
                                lineTo(corners[BR].x, corners[BR].y)
                                lineTo(corners[BL].x, corners[BL].y)
                                close()
                            }
                            val scrim = Path().apply {
                                op(fullRect, quad, PathOperation.Difference)
                            }
                            drawPath(scrim, color = Color.Black.copy(alpha = 0.55f))
                            drawPath(quad, color = Color(0xFF7C6FE0), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                        }

                        corners.forEachIndexed { index, corner ->
                            val handleSizePx = 28.dp
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (corner.x - handleSizePx.toPx() / 2).toInt(),
                                            (corner.y - handleSizePx.toPx() / 2).toInt()
                                        )
                                    }
                                    .size(handleSizePx)
                                    .clip(CircleShape)
                                    .background(Color(0xFF7C6FE0))
                                    .pointerInput(index) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            corners = corners.toMutableList().also {
                                                it[index] = it[index] + dragAmount
                                            }
                                        }
                                    }
                            )
                        }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            previewBitmap?.let { preview ->
                Text("Preview", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(12.dp)
                )
            }

            statusText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val warped = buildWarpedBitmap()
                        previewBitmap = warped
                        statusText = if (warped == null) "Couldn't build preview — adjust corners and try again" else null
                    }
                ) { Text("Preview") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
                    onClick = {
                        val warped = previewBitmap ?: buildWarpedBitmap()
                        if (warped == null) {
                            statusText = "Couldn't extract — adjust corners and try again"
                            return@Button
                        }
                        isProcessing = true
                        statusText = "Scanning text…"
                        scope.launch {
                            val text = TextScanHelper.recognizeTextFromBitmap(warped)
                            isProcessing = false
                            if (text != null) {
                                onExtractedText(text)
                            } else {
                                statusText = "No text found in the selected region"
                            }
                        }
                    }
                ) { Text(if (isProcessing) "Scanning…" else "Extract text") }
            }
        }
    }
}

private data class FitInfo(
    val fitScale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val drawnW: Float,
    val drawnH: Float
)

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
