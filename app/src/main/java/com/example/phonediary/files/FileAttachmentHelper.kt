package com.example.phonediary.files

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class SavedAttachment(val name: String, val uri: Uri)

object FileAttachmentHelper {

    private const val DEFAULT_SUBFOLDER = "PhoneDiary"

    /**
     * Copies a picked file into Downloads/<subfolder>.
     *
     * dedupe = true: if an identical file (same size + SHA-256) is already in
     * that folder under the same base name, it is reused instead of creating
     * "name (1)", "name (1) (1)", ... on every attach.
     *
     * The returned name is the name Android actually stored, which can differ
     * from the requested one when there is a name clash.
     */
    fun copyToDownloads(
        context: Context,
        sourceUri: Uri,
        forcedName: String? = null,
        subfolder: String = DEFAULT_SUBFOLDER,
        dedupe: Boolean = true
    ): SavedAttachment? {
        val displayName = forcedName ?: queryDisplayName(context, sourceUri) ?: "attachment_${System.currentTimeMillis()}"

        return try {
            if (dedupe) {
                findExistingDuplicate(context, sourceUri, displayName, subfolder)?.let { return it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyViaMediaStore(context, sourceUri, displayName, subfolder)
            } else {
                copyViaLegacyFile(context, sourceUri, displayName, subfolder)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Saves a Bitmap (e.g. a finger drawing) directly as a PNG attachment. */
    fun saveBitmapAsAttachment(context: Context, bitmap: Bitmap, forcedName: String? = null): SavedAttachment? {
        val displayName = forcedName ?: "drawing_${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_SUBFOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                }
                val resolver = context.contentResolver
                val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(destUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(destUri, values, null, null)
                SavedAttachment(actualDisplayName(context, destUri, displayName), destUri)
            } else {
                val dir = legacyDir(DEFAULT_SUBFOLDER)
                val destFile = uniqueFile(dir, displayName)
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                SavedAttachment(destFile.name, fileProviderUri(context, destFile))
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- dedupe

    private fun findExistingDuplicate(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        subfolder: String
    ): SavedAttachment? {
        val sourceSize = querySize(context, sourceUri)
        val sourceHash = sha256(context, sourceUri) ?: return null

        val base = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) ".${displayName.substringAfterLast('.')}" else ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/$subfolder"
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            )
            // Matches "name.ext" plus every auto-renamed "name (1).ext", "name (1) (1).ext", ...
            val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) " +
                "AND (${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
            val args = arrayOf(relative, "$relative/", displayName, "$base (%)$ext")

            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2)
                    if (sourceSize >= 0 && size != sourceSize) continue
                    val candidate = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    if (sha256(context, candidate) == sourceHash) return SavedAttachment(name, candidate)
                }
            }
        } else {
            val dir = legacyDir(subfolder)
            val regex = Regex("^" + Regex.escape(base) + "( \\(\\d+\\))*" + Regex.escape(ext) + "$")
            dir.listFiles()?.filter { it.isFile && (it.name == displayName || regex.matches(it.name)) }?.forEach { file ->
                if (sourceSize >= 0 && file.length() != sourceSize) return@forEach
                val candidate = fileProviderUri(context, file)
                if (sha256(context, candidate) == sourceHash) return SavedAttachment(file.name, candidate)
            }
        }
        return null
    }

    private fun sha256(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun querySize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return -1L
    }

    // ------------------------------------------------------------------ copy

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun mimeFor(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    private fun copyViaMediaStore(context: Context, sourceUri: Uri, displayName: String, subfolder: String): SavedAttachment? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder")
            put(MediaStore.Downloads.IS_PENDING, 1)
            mimeFor(displayName)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
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

        // MediaStore may have renamed the file on a clash — return the real name.
        return SavedAttachment(actualDisplayName(context, destUri, displayName), destUri)
    }

    private fun copyViaLegacyFile(context: Context, sourceUri: Uri, displayName: String, subfolder: String): SavedAttachment? {
        val dir = legacyDir(subfolder)
        val destFile = uniqueFile(dir, displayName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destFile).use { out -> input.copyTo(out) }
        }
        return SavedAttachment(destFile.name, fileProviderUri(context, destFile))
    }

    private fun actualDisplayName(context: Context, uri: Uri, fallback: String): String {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: fallback
        }
        return fallback
    }

    private fun legacyDir(subfolder: String): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subfolder)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
