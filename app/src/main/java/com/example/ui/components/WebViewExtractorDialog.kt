package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import android.webkit.WebSettings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.extractor.WebSessionCookieStore
import kotlinx.coroutines.delay
import java.util.Collections

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewExtractorDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onExtracted: (
        sourceUrl: String,
        pageTitle: String,
        imageUrls: List<String>,
        chapterLinks: List<Pair<String, String>>
    ) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val normalizedStartUrl = remember(initialUrl) {
        val trimmed = initialUrl.trim()
        when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.isNotBlank() -> "https://$trimmed"
            else -> "https://comix.to"
        }
    }

    var currentUrl by remember { mutableStateOf(normalizedStartUrl) }
    var currentTitle by remember { mutableStateOf("Navegador JS") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    // Passively records comic image URLs loaded over the network by SPA / Next.js / lazy-loaded readers
    val sniffedImageUrls = remember { Collections.synchronizedList(mutableListOf<String>()) }
    var sniffedCount by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    LaunchedEffect(isAutoScrolling) {
        while (isAutoScrolling) {
            webViewRef?.evaluateJavascript(
                """
                (function(){
                  if (typeof window.__PANELPULL_COLLECT_DOM === 'function') {
                    window.__PANELPULL_COLLECT_DOM();
                  }
                  var docEl = document.documentElement || document.body;
                  var scrollH = Math.max((docEl && docEl.scrollHeight) || 0, (document.body && document.body.scrollHeight) || 0);
                  var winH = window.innerHeight || 844;
                  var curY = window.scrollY || window.pageYOffset || 0;
                  window.scrollBy({top: Math.max(winH * 1.5, 1100), behavior: 'smooth'});
                  var domCount = window.__PANELPULL_DOM_MAP ? Object.keys(window.__PANELPULL_DOM_MAP).length : 0;
                  var apiCount = Array.isArray(window.__PANELPULL_API_IMAGES) ? window.__PANELPULL_API_IMAGES.length : 0;
                  var atBottom = (curY + winH >= scrollH - 80);
                  return JSON.stringify({ count: Math.max(domCount, apiCount), atBottom: atBottom });
                })();
                """.trimIndent()
            ) { raw ->
                runCatching {
                    val unquoted = if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                        org.json.JSONTokener(raw).nextValue().toString()
                    } else {
                        raw.orEmpty()
                    }
                    if (unquoted.startsWith("{")) {
                        val obj = org.json.JSONObject(unquoted)
                        val jsCount = obj.optInt("count", 0)
                        if (jsCount > sniffedCount) {
                            sniffedCount = jsCount
                        }
                        if (obj.optBoolean("atBottom", false)) {
                            isAutoScrolling = false
                        }
                    }
                }
            }
            delay(700L)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (sniffedCount > 0) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "$sniffedCount img",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = currentUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            isAutoScrolling = false
                            onDismiss()
                        },
                        modifier = Modifier.testTag("webview_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Fechar navegador"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            sniffedImageUrls.clear()
                            sniffedCount = 0
                            webViewRef?.reload()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Recarregar página"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { isAutoScrolling = !isAutoScrolling },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("webview_scroll_button")
                    ) {
                        Icon(
                            imageVector = if (isAutoScrolling) Icons.Default.Pause else Icons.Default.ArrowDownward,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isAutoScrolling) "Pausar" else "Auto-Rolar", maxLines = 1)
                    }

                    Button(
                        onClick = {
                            isAutoScrolling = false
                            WebSessionCookieStore.syncFromWebView(currentUrl)
                            val js = WebViewJsScripts.buildSmartExtractionScript()
                            webViewRef?.evaluateJavascript(js) { rawJson ->
                                val sniffedSnapshot = synchronized(sniffedImageUrls) {
                                    sniffedImageUrls.toList()
                                }
                                val parsed = WebViewJsScripts.parseExtractionJson(
                                    rawJson = rawJson,
                                    fallbackUrl = currentUrl,
                                    fallbackTitle = currentTitle,
                                    sniffedSnapshot = sniffedSnapshot
                                )
                                WebSessionCookieStore.syncFromWebView(parsed.url)
                                onExtracted(parsed.url, parsed.title, parsed.images, parsed.chapters)
                            }
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("webview_capture_button")
                    ) {
                        Icon(Icons.Default.Collections, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Capturar Imagens", maxLines = 1)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        runCatching {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = ComicExtractorEngine.BROWSER_USER_AGENT
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) currentTitle = title
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (!url.isNullOrBlank()) {
                                    if (url != currentUrl) {
                                        sniffedImageUrls.clear()
                                        sniffedCount = 0
                                    }
                                    currentUrl = url
                                }
                                view?.evaluateJavascript(WebViewJsScripts.buildNetworkHookScript(), null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) {
                                    WebSessionCookieStore.syncFromWebView(url)
                                }
                                view?.evaluateJavascript(WebViewJsScripts.buildNetworkHookScript(), null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (WebViewJsScripts.isLikelySniffedComicImage(reqUrl)) {
                                    WebSessionCookieStore.syncFromWebView(reqUrl)
                                    synchronized(sniffedImageUrls) {
                                        if (!sniffedImageUrls.contains(reqUrl)) {
                                            sniffedImageUrls.add(reqUrl)
                                            sniffedCount = sniffedImageUrls.size
                                        }
                                    }
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                        loadUrl(normalizedStartUrl)
                        webViewRef = this
                    }
                }
            )
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}
