package com.example.phonediary.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

data class SavedAttachment(val name: String, val uri: Uri)

object FileAttachmentHelper {

    private const val DEFAULT_SUBFOLDER = "PhoneDiary"

    fun copyToDownloads(
        context: Context,
        sourceUri: Uri,
        forcedName: String? = null,
        subfolder: String = DEFAULT_SUBFOLDER
    ): SavedAttachment? {
        val displayName = forcedName ?: queryDisplayName(context, sourceUri) ?: "attachment_${System.currentTimeMillis()}"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyViaMediaStore(context, sourceUri, displayName, subfolder)
            } else {
                copyViaLegacyFile(context, sourceUri, displayName, subfolder)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun copyViaMediaStore(context: Context, sourceUri: Uri, displayName: String, subfolder: String): SavedAttachment? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        resolver.openOutputStream(destUri)?.use { out ->
            resolver.openInputStream(sourceUri)?.use { input ->
                input.copyTo(out)
            }
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(destUri, values, null, null)

        return SavedAttachment(displayName, destUri)
    }

    private fun copyViaLegacyFile(context: Context, sourceUri: Uri, displayName: String, subfolder: String): SavedAttachment? {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            subfolder
        )
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val destFile = File(downloadsDir, displayName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destFile).use { out ->
                input.copyTo(out)
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", destFile
        )
        return SavedAttachment(displayName, uri)
    }
}
