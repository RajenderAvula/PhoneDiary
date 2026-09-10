package com.example.phonediary.files

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import java.io.File

/**
 * Records audio to a temp file, then hands it to FileAttachmentHelper to
 * copy into Downloads/PhoneDiary alongside regular file attachments.
 */
class AudioRecorderHelper(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null

    fun startRecording() {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        tempFile = file

        val mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
    }

    /** Stops recording and copies the result into Downloads/PhoneDiary. Returns the saved display name, or null on failure. */
    fun stopRecordingAndSave(): String? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null

            val file = tempFile ?: return null
            val savedName = "voice_note_${System.currentTimeMillis()}.m4a"
            val result = FileAttachmentHelper.copyToDownloads(context, Uri.fromFile(file), savedName)
            file.delete()
            result
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            null
        }
    }

    fun cancelRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // ignore — recorder may not have started successfully
        }
        recorder = null
        tempFile?.delete()
        tempFile = null
    }
}
