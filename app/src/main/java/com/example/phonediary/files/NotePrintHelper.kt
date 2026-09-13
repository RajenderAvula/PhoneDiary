package com.example.phonediary.files

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Prints a note's text using Android's built-in print framework — renders
 * the note into a hidden WebView, then hands it to the system print
 * dialog (which supports "Save as PDF" as one of its printers).
 */
object NotePrintHelper {

    fun printNote(context: Context, title: String, noteText: String, tags: List<String>) {
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val jobName = "PhoneDiary_${System.currentTimeMillis()}"
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder().build()
                )
            }
        }

        val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(System.currentTimeMillis())
        val tagsHtml = if (tags.isNotEmpty()) {
            "<p style=\"color:#666;\">Tags: ${tags.joinToString(", ") { "#$it" }}</p>"
        } else ""

        val html = """
            <html>
            <body style="font-family: sans-serif; padding: 24px;">
                <h2>${escapeHtml(title)}</h2>
                <p style="color:#999; font-size: 12px;">$dateStr</p>
                $tagsHtml
                <hr/>
                <p style="white-space: pre-wrap; font-size: 16px; line-height: 1.5;">${escapeHtml(noteText)}</p>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
