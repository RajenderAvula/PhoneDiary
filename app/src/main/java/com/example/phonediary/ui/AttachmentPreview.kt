package com.example.phonediary.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val audioExtensions = setOf("mp3", "m4a", "wav", "ogg", "aac")

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/**
 * Presentational: shows exactly what it's given.
 * - uri == null && showLoading == true  -> spinner (still resolving)
 * - uri == null && showLoading == false -> "not found" state, no infinite spin
 * - uri != null                         -> thumbnail/player/open-with, tappable
 */
@Composable
fun AttachmentPreview(
    name: String,
    uri: Uri?,
    showLoading: Boolean = (uri == null),
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val ext = remember(name) { extensionOf(name) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .then(
                    if (uri != null) Modifier.clickable { openWithChooser(context, uri, name) }
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                uri == null && showLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                uri == null -> {
                    Text("⚠", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "File not found — it may have been moved or deleted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                ext in imageExtensions -> {
                    ImageThumbnail(uri)
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                ext in audioExtensions -> {
                    AudioPlayButton(uri)
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                else -> {
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("Open ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            onRemove?.let {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = it) { Text("✕") }
            }
        }
    }
}

/**
 * Self-resolving wrapper: looks up [name] via MediaResolveUtil with a
 * hard timeout, then renders AttachmentPreview with showLoading correctly
 * set to false once resolution finishes — whether it succeeded or not.
 * Use this everywhere instead of hand-rolling a LaunchedEffect, so a
 * failed/slow lookup can never spin forever again.
 */
@Composable
fun ResolvingAttachmentPreview(
    name: String,
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
    var resolutionFinished by remember(name) { mutableStateOf(false) }

    LaunchedEffect(name) {
        resolvedUri = withTimeoutOrNull(6000) {
            withContext(Dispatchers.IO) {
                try {
                    com.example.phonediary.files.MediaResolveUtil.resolve(context, name)
                } catch (e: Throwable) {
                    null
                }
            }
        }
        resolutionFinished = true
    }

    AttachmentPreview(
        name = name,
        uri = resolvedUri,
        showLoading = !resolutionFinished,
        onRemove = onRemove
    )
}

@Composable
private fun ImageThumbnail(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = withTimeoutOrNull(5000) {
            withContext(Dispatchers.IO) {
                try { decodeSampledBitmap(context, uri, 160, 160) } catch (e: Throwable) { null }
            }
        }
        if (bitmap == null) failed = true
    }

    Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            failed -> Text("🖼", style = MaterialTheme.typography.titleMedium)
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun decodeSampledBitmap(context: android.content.Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
    val resolver = context.contentResolver
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) } ?: return null

    var sampleSize = 1
    val halfWidth = boundsOptions.outWidth / 2
    val halfHeight = boundsOptions.outHeight / 2
    while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
}

@Composable
private fun AudioPlayButton(uri: Uri) {
    val context = LocalContext.current
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(uri) {
        onDispose { player?.release(); player = null }
    }

    IconButton(onClick = {
        if (isPlaying) {
            player?.stop(); player?.release(); player = null
            isPlaying = false
        } else {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(context, uri)
                mp.setOnCompletionListener {
                    isPlaying = false; player?.release(); player = null
                }
                mp.prepare(); mp.start()
                player = mp
                isPlaying = true
            } catch (e: Exception) {
                isPlaying = false
            }
        }
    }) {
        Text(if (isPlaying) "⏸" else "▶")
    }
}

private fun openWithChooser(context: android.content.Context, uri: Uri, name: String) {
    try {
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extensionOf(name))
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        // No app can handle it — ignore.
    }
}
