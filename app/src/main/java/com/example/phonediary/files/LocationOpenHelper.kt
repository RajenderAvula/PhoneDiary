package com.example.phonediary.files

import android.content.Context
import android.content.Intent
import android.net.Uri

object LocationOpenHelper {
    fun open(context: Context, rawUrl: String) {
        val url = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else rawUrl
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No app can handle it — silently ignore rather than crash.
        }
    }
}
