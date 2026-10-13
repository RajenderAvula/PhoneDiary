package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val GOOGLE_HOME = "https://www.google.com"

private fun toUrl(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return GOOGLE_HOME
    val looksLikeAddress = !text.contains(' ') &&
        (text.startsWith("http://") || text.startsWith("https://") || text.contains('.'))
    return when {
        looksLikeAddress && (text.startsWith("http://") || text.startsWith("https://")) -> text
        looksLikeAddress -> "https://$text"
        else -> "https://www.google.com/search?q=${Uri.encode(text)}"
    }
}

/**
 * Owns the WebView so it outlives the tab's composition. The tab is only
 * composed while selected (a native view can't be hidden by shrinking it),
 * and this keeps the page, history and scroll position between visits.
 */
class WebSearchState(private val context: Context) {
    var currentUrl by mutableStateOf(GOOGLE_HOME)
    var progress by mutableStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var webView: WebView? = null
        private set

    fun obtain(): WebView {
        webView?.let { return it }
       // return WebView(context).apply {
       val state = this
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    if (scheme == "http" || scheme == "https") return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { state.currentUrl = url }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    state.currentUrl = url
                    state.canBack = view.canGoBack()
                    state.canForward = view.canGoForward()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { state.progress = newProgress }
            }
            setDownloadListener { url, _, _, _, _ ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            loadUrl(GOOGLE_HOME)
            state.webView = this
        }
    }

    fun destroy() {
        webView?.apply { (parent as? ViewGroup)?.removeView(this); stopLoading(); destroy() }
        webView = null
    }

    fun clearAll() {
        webView?.apply { clearCache(true); clearHistory() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        webView?.loadUrl(GOOGLE_HOME)
    }
}

@Composable
fun WebSearchTab(state: WebSearchState) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    BackHandler(enabled = state.canBack) { state.webView?.goBack() }

    fun go(raw: String) {
        state.webView?.loadUrl(toUrl(raw))
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = if (editing) input else state.currentUrl,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .onFocusChanged { s ->
                    if (s.isFocused && !editing) { editing = true; input = "" }
                    if (!s.isFocused) editing = false
                },
            singleLine = true,
            placeholder = { Text("Search Google or type a website") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go(input) }),
            trailingIcon = {
                if (editing && input.isNotEmpty()) TextButton(onClick = { input = "" }) { Text("✕") }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(enabled = state.canBack, onClick = { state.webView?.goBack() }) { Text("◀") }
            IconButton(enabled = state.canForward, onClick = { state.webView?.goForward() }) { Text("▶") }
            IconButton(onClick = { state.webView?.reload() }) { Text("⟳") }
            IconButton(onClick = { state.webView?.loadUrl(GOOGLE_HOME) }) { Text("🏠") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.currentUrl))) }
            }) { Text("Open in browser") }
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }

        if (state.progress in 1..99) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = {
                state.obtain().also { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.onResume()
                }
            },
            onRelease = { wv ->
                wv.onPause()
                (wv.parent as? ViewGroup)?.removeView(wv)
            }
        )
    }

    if (confirmClear) {
        ConfirmDeleteDialog(
            message = "Clear this tab's cookies, cache and history? You will be signed out of sites you logged in to here.",
            onConfirm = { confirmClear = false; state.clearAll() },
            onDismiss = { confirmClear = false }
        )
    }
}
