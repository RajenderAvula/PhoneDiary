package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object MediaResolveUtil {

    /** Resolves a saved attachment's filename back to a content Uri it can be opened/previewed from. */
    fun resolve(context: Context, fileName: String): Uri? {
        resolveFromCollection(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileName)?.let { return it }
        resolveFromCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileName)?.let { return it }
        resolveFromCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileName)?.let { return it }
        resolveFromCollection(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileName)?.let { return it }

        // Fallback: the file may have been written directly to disk (legacy
        // pre-Android-10 path, or a MediaStore row that hasn't been indexed
        // yet) rather than being queryable via MediaStore at all. Check the
        // known subfolders directly rather than returning null forever.
        resolveFromFilesystem(context, fileName)?.let { return it }

        return null
    }

    private fun resolveFromCollection(context: Context, collection: Uri, fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        return try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    Uri.withAppendedPath(collection, id.toString())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFromFilesystem(context: Context, fileName: String): Uri? {
        val candidateDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PhoneDiary"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PhoneDiary/backups"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PhoneDiary")
        )
        for (dir in candidateDirs) {
            val file = File(dir, fileName)
            if (file.exists()) {
                return try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }
}
