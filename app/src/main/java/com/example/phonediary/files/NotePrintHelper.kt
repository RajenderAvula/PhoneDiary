package com.example.phonediary.files

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.phonediary.ui.repeatDisplayLabel2
import java.text.SimpleDateFormat
import java.util.Locale

object NotePrintHelper {

    fun printNote(
        context: Context,
        title: String,
        noteText: String,
        tags: List<String>,
        locationUrl: String? = null,
        reminderAtMillis: Long? = null,
        dueAtMillis: Long? = null,
        repeatRule: String? = null,
        attachmentNames: List<String> = emptyList()
    ) {
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val jobName = "PhoneDiary_${System.currentTimeMillis()}"
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
            }
        }

        val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        val printedAt = dateFormat.format(System.currentTimeMillis())

        val tagsHtml = if (tags.isNotEmpty()) {
            "<p><b>Tags:</b> ${tags.joinToString(", ") { "#$it" }}</p>"
        } else ""
        val locationHtml = locationUrl?.takeIf { it.isNotBlank() }?.let {
            "<p><b>Location:</b> ${escapeHtml(it)}</p>"
        } ?: ""
        val reminderHtml = reminderAtMillis?.let {
            "<p><b>Reminder:</b> ${dateFormat.format(it)}</p>"
        } ?: ""
        val dueHtml = dueAtMillis?.let {
            "<p><b>Due:</b> ${dateFormat.format(it)}</p>"
        } ?: ""
        val repeatHtml = repeatRule?.takeIf { it != "NONE" }?.let {
            "<p><b>Repeat:</b> ${escapeHtml(repeatDisplayLabel2(it))}</p>"
        } ?: ""
        val attachmentsHtml = if (attachmentNames.isNotEmpty()) {
            "<p><b>Attachments:</b> ${attachmentNames.joinToString(", ") { escapeHtml(it) }}</p>"
        } else ""

        val schemaBlock = listOf(locationHtml, tagsHtml, reminderHtml, dueHtml, repeatHtml, attachmentsHtml)
            .filter { it.isNotBlank() }
            .joinToString("")

        val html = """
            <html>
            <body style="font-family: sans-serif; padding: 24px; color: #222;">
                <h2>${escapeHtml(title)}</h2>
                <p style="color:#999; font-size: 12px;">Printed: $printedAt</p>
                <hr/>
                <p style="white-space: pre-wrap; font-size: 16px; line-height: 1.5;">${escapeHtml(noteText)}</p>
                ${if (schemaBlock.isNotBlank()) "<hr/><h3>Details</h3>$schemaBlock" else ""}
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
