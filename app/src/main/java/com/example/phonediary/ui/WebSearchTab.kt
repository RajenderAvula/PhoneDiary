package com.example.phonediary.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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

/** Turns what the user typed into a page address: a site if it looks like one, otherwise a Google search. */
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

@Composable
fun WebSearchTab(isVisible: Boolean) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var started by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(GOOGLE_HOME) }
    var progress by remember { mutableStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Load nothing until the tab is first opened.
    LaunchedEffect(isVisible) {
        if (isVisible) started = true
        if (isVisible) webView?.onResume() else webView?.onPause()
    }

    BackHandler(enabled = isVisible && canBack) { webView?.goBack() }

    fun go(raw: String) {
        webView?.loadUrl(toUrl(raw))
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = if (editing) input else currentUrl,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .onFocusChanged { state ->
                    if (state.isFocused && !editing) { editing = true; input = "" }
                    if (!state.isFocused) editing = false
                },
            singleLine = true,
            placeholder = { Text("Search Google or type a website") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go(input) }),
            trailingIcon = {
                if (editing && input.isNotEmpty()) {
                    TextButton(onClick = { input = "" }) { Text("✕") }
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(enabled = canBack, onClick = { webView?.goBack() }) { Text("◀") }
            IconButton(enabled = canForward, onClick = { webView?.goForward() }) { Text("▶") }
            IconButton(onClick = { webView?.reload() }) { Text("⟳") }
            IconButton(onClick = { webView?.loadUrl(GOOGLE_HOME) }) { Text("🏠") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                }
            }) { Text("Open in browser") }
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        if (started) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true      // Google's pages need it
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false       // never let a page read local files
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest
                            ): Boolean {
                                val scheme = request.url.scheme.orEmpty()
                                if (scheme == "http" || scheme == "https") return false
                                // mailto:, tel:, app links… go to the phone, never run inside the page.
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                currentUrl = url
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                canBack = view.canGoBack()
                                canForward = view.canGoForward()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        setDownloadListener { url, _, _, _, _ ->
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                        webView = this
                        loadUrl(GOOGLE_HOME)
                    }
                }
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); destroy() }
            webView = null
        }
    }

    if (confirmClear) {
        ConfirmDeleteDialog(
            message = "Clear this tab's cookies, cache and history? You will be signed out of sites you logged in to here.",
            onConfirm = {
                confirmClear = false
                webView?.clearCache(true)
                webView?.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                webView?.loadUrl(GOOGLE_HOME)
            },
            onDismiss = { confirmClear = false }
        )
    }
}
