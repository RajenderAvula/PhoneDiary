package com.example.phonediary.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object MediaResolveUtil {

    /** Looks up the content Uri for a previously-saved attachment by its filename. */
    fun resolve(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        // Try Downloads first, then Movies (videos land there instead).
        resolveInCollection(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileName)?.let { return it }
        resolveInCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileName)?.let { return it }
        return null
    }

    private fun resolveInCollection(context: Context, collection: Uri, fileName: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
