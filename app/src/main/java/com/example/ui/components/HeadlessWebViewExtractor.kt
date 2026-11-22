package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.extractor.WebSessionCookieStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Offscreen Chromium-backed extractor that automatically handles SPA / Next.js App Router /
 * obfuscated-API comic websites (like comix.to) without requiring manual WebView interaction.
 *
 * Uses a realistic mobile viewport offscreen (390x844 dp, alpha 0) so IntersectionObserver,
 * lazy-loaded reader images, and responsive layouts hydrate accurately.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeadlessWebViewExtractor(
    url: String,
    onStageUpdate: (String) -> Unit,
    onFinished: (
        sourceUrl: String,
        pageTitle: String,
        imageUrls: List<String>,
        chapterLinks: List<Pair<String, String>>
    ) -> Unit
) {
    val latestOnStage by rememberUpdatedState(onStageUpdate)
    val latestOnFinished by rememberUpdatedState(onFinished)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var currentTitle by remember(url) { mutableStateOf("") }
    var pageFinishedOnce by remember(url) { mutableStateOf(false) }

    val sniffedImageUrls = remember(url) { Collections.synchronizedList(mutableListOf<String>()) }

    DisposableEffect(url) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    LaunchedEffect(url, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect

        var bestPayload = ParsedWebViewPayload(
            url = url,
            title = "",
            images = emptyList(),
            chapters = emptyList(),
            source = "none",
            readyState = "loading"
        )
        var lastCount = 0
        var stableTicks = 0
        var scrolledSteps = 0

        // Poll up to ~18 seconds (20 ticks x 900ms)
        for (tick in 0 until 20) {
            delay(900L)

            // Ensure network hook is active even if JS replaced window state during SPA navigation
            wv.evaluateJavascript(WebViewJsScripts.buildNetworkHookScript(), null)

            if (tick == 1) {
                latestOnStage("Executando JavaScript da página…")
            } else if (tick == 4) {
                latestOnStage("Aguardando carregamento das imagens do capítulo…")
            } else if (tick == 9) {
                latestOnStage("Capturando páginas dinâmicas (modo automático)…")
            }

            // Gently scroll to trigger IntersectionObserver / lazy-loading comic readers
            if (pageFinishedOnce && scrolledSteps < 8 && tick >= 2) {
                wv.evaluateJavascript(
                    "(function(){ window.scrollBy(0, 1400); })();",
                    null
                )
                scrolledSteps++
            }

            val rawJson = evaluateJsSuspend(wv, WebViewJsScripts.buildSmartExtractionScript())
            val sniffedSnapshot = synchronized(sniffedImageUrls) { sniffedImageUrls.toList() }
            val parsed = WebViewJsScripts.parseExtractionJson(
                rawJson = rawJson,
                fallbackUrl = currentUrl,
                fallbackTitle = currentTitle,
                sniffedSnapshot = sniffedSnapshot
            )

            if (parsed.images.size >= bestPayload.images.size) {
                bestPayload = parsed.copy(
                    chapters = if (parsed.chapters.isNotEmpty()) parsed.chapters else bestPayload.chapters
                )
            } else if (parsed.chapters.size > bestPayload.chapters.size) {
                bestPayload = bestPayload.copy(chapters = parsed.chapters)
            }

            val count = bestPayload.images.size
            if (count > 0) {
                latestOnStage("$count páginas detectadas, finalizando…")
            }

            // Fast completion when structured API or stitched __next_f already delivered full page array
            if ((bestPayload.source == "api" || bestPayload.source == "next_f") && count >= 2) {
                delay(700L)
                // One last read to also pick up any chapter links that arrived alongside images
                val finalRaw = evaluateJsSuspend(wv, WebViewJsScripts.buildSmartExtractionScript())
                val finalSniff = synchronized(sniffedImageUrls) { sniffedImageUrls.toList() }
                val finalParsed = WebViewJsScripts.parseExtractionJson(
                    rawJson = finalRaw,
                    fallbackUrl = currentUrl,
                    fallbackTitle = currentTitle,
                    sniffedSnapshot = finalSniff
                )
                if (finalParsed.images.size >= bestPayload.images.size) {
                    bestPayload = finalParsed.copy(
                        chapters = if (finalParsed.chapters.isNotEmpty()) finalParsed.chapters else bestPayload.chapters
                    )
                }
                break
            }

            // DOM / Sniffed completion: wait until page count settles for 2 consecutive ticks
            if (count >= 3) {
                if (count == lastCount) {
                    stableTicks++
                    if (stableTicks >= 2 && scrolledSteps >= 2) {
                        break
                    }
                } else {
                    stableTicks = 0
                }
            } else if (count == 0 && bestPayload.chapters.isNotEmpty() && pageFinishedOnce && tick >= 5) {
                // Series index page with chapter links and no direct reader pages
                break
            }
            lastCount = count
        }

        val finalUrl = bestPayload.url.ifBlank { currentUrl }
        WebSessionCookieStore.syncFromWebView(finalUrl)
        latestOnFinished(
            finalUrl,
            bestPayload.title.ifBlank { currentTitle },
            bestPayload.images,
            bestPayload.chapters
        )
    }

    AndroidView(
        modifier = Modifier
            .size(width = 390.dp, height = 844.dp)
            .offset(x = (-2500).dp)
            .alpha(0f),
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
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!title.isNullOrBlank()) currentTitle = title
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        if (!startedUrl.isNullOrBlank()) {
                            currentUrl = startedUrl
                        }
                        view?.evaluateJavascript(WebViewJsScripts.buildNetworkHookScript(), null)
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        pageFinishedOnce = true
                        if (!finishedUrl.isNullOrBlank()) {
                            currentUrl = finishedUrl
                            WebSessionCookieStore.syncFromWebView(finishedUrl)
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

                loadUrl(url)
                webViewRef = this
            }
        }
    )
}

private suspend fun evaluateJsSuspend(webView: WebView, script: String): String? =
    suspendCancellableCoroutine { cont ->
        webView.post {
            try {
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
