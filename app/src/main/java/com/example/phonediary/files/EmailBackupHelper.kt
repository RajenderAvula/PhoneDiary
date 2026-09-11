package com.example.phonediary.files

import android.content.Context
import android.content.Intent
import android.net.Uri

object EmailBackupHelper {

    fun shareBackupViaEmail(context: Context, zipUri: Uri, fileName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri)
            putExtra(Intent.EXTRA_SUBJECT, "Phone Diary Backup - $fileName")
            putExtra(Intent.EXTRA_TEXT, "Attached: Phone Diary backup created on this device.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val gmailIntent = Intent(intent).setPackage("com.google.android.gm")
        try {
            context.startActivity(gmailIntent)
        } catch (e: Exception) {
            context.startActivity(Intent.createChooser(intent, "Send backup via"))
        }
    }
}
