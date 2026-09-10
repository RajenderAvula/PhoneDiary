package com.example.phonediary.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Prepares a destination Uri for the camera app to record video into,
 * saved under Movies/PhoneDiary (Android 10+) or via FileProvider on
 * older versions.
 */
object VideoCaptureHelper {

    private const val SUBFOLDER = "PhoneDiary"

    fun createVideoOutputUri(context: Context): Pair<Uri, String>? {
        val displayName = "video_${System.currentTimeMillis()}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            uri to displayName
        } else {
            val moviesDir = java.io.File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                SUBFOLDER
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val file = java.io.File(moviesDir, displayName)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uri to displayName
        }
    }
}
