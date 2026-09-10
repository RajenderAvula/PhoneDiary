package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "aac", "ogg")
private val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mkv", "webm")

private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

/**
 * Shows a small preview for one attachment: a thumbnail for images, an
 * inline play/pause for audio, and an "Open" action (system player/viewer)
 * for video and anything else. Works for both pending (Uri known directly)
 * and already-saved (Uri resolved by filename) attachments.
 */
@Composable
fun AttachmentPreview(name: String, uri: Uri?, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val ext = extensionOf(name)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        when {
            uri != null && ext in IMAGE_EXTENSIONS -> ImageThumbnail(uri)
            ext in AUDIO_EXTENSIONS -> Text("🎵", modifier = Modifier.padding(end = 8.dp))
            ext in VIDEO_EXTENSIONS -> Text("🎬", modifier = Modifier.padding(end = 8.dp))
            else -> Text("📄", modifier = Modifier.padding(end = 8.dp))
        }

        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))

        if (uri != null) {
            when (ext) {
                in AUDIO_EXTENSIONS -> AudioPlayButton(uri)
                in VIDEO_EXTENSIONS, !in IMAGE_EXTENSIONS -> {
                    if (ext !in IMAGE_EXTENSIONS) {
                        TextButton(onClick = { openWithSystemViewer(context, uri, ext) }) { Text("Open") }
                    }
                }
            }
        }

        onRemove?.let {
            TextButton(onClick = it) { Text("✕") }
        }
    }
}

@Composable
private fun ImageThumbnail(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(48.dp).padding(end = 8.dp)
        )
    } ?: Text("🖼", modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun AudioPlayButton(uri: Uri) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(uri) {
        onDispose {
            player?.release()
            player = null
        }
    }

    TextButton(onClick = {
        if (isPlaying) {
            player?.stop()
            player?.release()
            player = null
            isPlaying = false
        } else {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setOnCompletionListener {
                        isPlaying = false
                    }
                    prepare()
                    start()
                }
                player = mp
                isPlaying = true
            } catch (e: Exception) {
                isPlaying = false
            }
        }
    }) {
        Text(if (isPlaying) "⏸ Pause" else "▶ Play")
    }
}

private fun openWithSystemViewer(context: Context, uri: Uri, ext: String) {
    val mimeType = when (ext) {
        in VIDEO_EXTENSIONS -> "video/*"
        "pdf" -> "application/pdf"
        else -> "*/*"
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No app available to handle this file type — silently ignore.
    }
}
