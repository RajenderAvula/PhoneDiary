package com.example.phonediary.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reference-counted deletion of attachment files. A file is removed from
 * Downloads/PhoneDiary (or Movies/PhoneDiary) only when no note and no
 * location-reminder note still references it by name.
 */
object AttachmentCleanup {

    private const val DOWNLOAD_PATH = "Download/PhoneDiary"
    private const val MOVIES_PATH = "Movies/PhoneDiary"
    /** The "unused files" sweep skips files newer than this, so an unsaved note's attachments are safe. */
    private const val MIN_AGE_SECONDS_FOR_SWEEP = 30 * 60L

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every attachment name a note uses: its own list plus its location-reminder notes. */
    fun namesOf(entry: LogEntry): Set<String> {
        val names = linkedSetOf<String>()
        names += AttachmentListUtil.toList(entry.attachmentFileName)
        LocationReminderListUtil.fromStored(entry.locationReminders).forEach { names += it.attachmentNames }
        return names
    }

    private suspend fun referencedNames(context: Context): Set<String> {
        val all = AppDatabase.getInstance(context).logEntryDao().getAllEntries()
        val names = mutableSetOf<String>()
        all.forEach { names += namesOf(it) }
        return names
    }

    /** Deletes each named file that no note references. Returns how many files were deleted. */
    suspend fun deleteIfUnreferenced(context: Context, names: Collection<String>): Int =
        withContext(Dispatchers.IO) {
            val candidates = names.filter { it.isNotBlank() }.toSet()
            if (candidates.isEmpty()) return@withContext 0
            val referenced = referencedNames(context)
            candidates.filter { it !in referenced }.count { deleteStoredFile(context, it) }
        }

    /** Fire-and-forget version for UI callbacks; survives the calling screen closing. */
    fun launchDeleteIfUnreferenced(context: Context, names: Collection<String>) {
        val app = context.applicationContext
        val copy = names.toList()
        cleanupScope.launch { deleteIfUnreferenced(app, copy) }
    }

    // ---- Sweep for files nothing references (e.g. leftovers from before this fix) ----

    suspend fun findUnreferencedFiles(context: Context): List<String> = withContext(Dispatchers.IO) {
        val referenced = referencedNames(context)
        val nowSeconds = System.currentTimeMillis() / 1000
        listStoredFiles(context)
            .filter { (name, addedSeconds) -> name !in referenced && nowSeconds - addedSeconds >= MIN_AGE_SECONDS_FOR_SWEEP }
            .map { it.first }
            .distinct()
            .sorted()
    }

    suspend fun deleteUnreferencedFiles(context: Context): Int = withContext(Dispatchers.IO) {
        findUnreferencedFiles(context).count { deleteStoredFile(context, it) }
    }

    // ---- Storage access (restricted to this app's own folders) ----

    private fun listStoredFiles(context: Context): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targets = listOf(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to DOWNLOAD_PATH,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MOVIES_PATH
            )
            for ((collection, path) in targets) {
                try {
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(path, "$path/"),
                        null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(0) ?: continue
                            result += name to c.getLong(1)
                        }
                    }
                } catch (e: Exception) {
                    // Collection unreadable — skip it.
                }
            }
        } else {
            legacyDirs().forEach { dir ->
                dir.listFiles()?.filter { it.isFile }?.forEach { result += it.name to (it.lastModified() / 1000) }
            }
        }
        return result
    }

    private fun deleteStoredFile(context: Context, name: String): Boolean {
        var deleted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targets = listOf(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to DOWNLOAD_PATH,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MOVIES_PATH
            )
            for ((collection, path) in targets) {
                val ids = mutableListOf<Long>()
                try {
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)",
                        arrayOf(name, path, "$path/"),
                        null
                    )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                } catch (e: Exception) {
                    // Skip this collection.
                }
                ids.forEach { id ->
                    try {
                        val uri: Uri = ContentUris.withAppendedId(collection, id)
                        if (context.contentResolver.delete(uri, null, null) > 0) deleted = true
                    } catch (e: Exception) {
                        // Android refuses deleting files this install doesn't own (e.g. made before a reinstall).
                    }
                }
            }
        } else {
            legacyDirs().forEach { dir ->
                val file = File(dir, name)
                if (file.exists() && file.delete()) deleted = true
            }
        }
        return deleted
    }

    private fun legacyDirs(): List<File> = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PhoneDiary"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PhoneDiary")
    )
}
