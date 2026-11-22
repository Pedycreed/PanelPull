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
        val cumulativeImages = LinkedHashSet<String>()
        var lastCount = 0
        var stableBottomTicks = 0
        var stableStructuredTicks = 0
        var scrolledSteps = 0

        // Poll up to ~32 seconds (50 ticks x ~650ms) so long 80–200 page webtoon chapters sweep completely
        for (tick in 0 until 50) {
            delay(if (tick < 3) 850L else 600L)

            // Ensure network + DOM mutation/scroll hook is active even after SPA route transitions
            wv.evaluateJavascript(WebViewJsScripts.buildNetworkHookScript(), null)

            if (tick == 1) {
                latestOnStage("Executando JavaScript da página…")
            } else if (tick == 3 && cumulativeImages.isEmpty()) {
                latestOnStage("Aguardando carregamento das imagens do capítulo…")
            }

            // Adaptive full-chapter scroll: sweep down through the entire webtoon/manhwa strip
            // so IntersectionObserver, lazy-loaders, and virtualized React lists hydrate every page.
            if ((pageFinishedOnce || tick >= 3) && tick >= 2) {
                wv.evaluateJavascript(
                    """
                    (function() {
                      if (typeof window.__PANELPULL_COLLECT_DOM === 'function') {
                        window.__PANELPULL_COLLECT_DOM();
                      }
                      var docEl = document.documentElement || document.body;
                      var scrollH = Math.max((docEl && docEl.scrollHeight) || 0, (document.body && document.body.scrollHeight) || 0);
                      var winH = window.innerHeight || 844;
                      var step = Math.max(Math.round(winH * 2.2), Math.min(4500, Math.round(scrollH / 16)));
                      window.scrollBy(0, step);
                      if (typeof window.__PANELPULL_COLLECT_DOM === 'function') {
                        setTimeout(window.__PANELPULL_COLLECT_DOM, 180);
                      }
                    })();
                    """.trimIndent(),
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

            // If structured API/next_f returned a clean larger list, make it the ordered base;
            // otherwise accumulate across ticks so virtualized readers never lose earlier pages.
            if ((parsed.source == "api" || parsed.source == "next_f") && parsed.images.size >= cumulativeImages.size) {
                cumulativeImages.clear()
                cumulativeImages.addAll(parsed.images)
            } else {
                cumulativeImages.addAll(parsed.images)
            }

            bestPayload = parsed.copy(
                images = cumulativeImages.toList(),
                chapters = if (parsed.chapters.size >= bestPayload.chapters.size) parsed.chapters else bestPayload.chapters
            )

            val count = bestPayload.images.size
            if (count > 0) {
                val pctSuffix = if (parsed.scrollProgressPercent in 1..99) " (${parsed.scrollProgressPercent}%)" else ""
                latestOnStage("Varrendo capítulo… $count páginas detectadas$pctSuffix")
            }

            // Completion condition 1: Structured API or __next_f delivered pages AND we either reached
            // the bottom of the chapter or swept at least 70% with no new pages arriving for 5+ ticks.
            val isStructured = bestPayload.source == "api" || bestPayload.source == "next_f"
            if (isStructured && count >= 2) {
                if (count == lastCount) {
                    stableStructuredTicks++
                    val sweptEnough = parsed.atBottom || parsed.scrollProgressPercent >= 85 || scrolledSteps >= 12
                    if ((parsed.atBottom && stableStructuredTicks >= 2) || (sweptEnough && stableStructuredTicks >= 5)) {
                        break
                    }
                } else {
                    stableStructuredTicks = 0
                }
            }

            // Completion condition 2: DOM / Sniffed reader reached the bottom of the chapter and count settled
            if (count >= 2 && parsed.atBottom && scrolledSteps >= 2) {
                if (count == lastCount) {
                    stableBottomTicks++
                    if (stableBottomTicks >= 3) {
                        break
                    }
                } else {
                    stableBottomTicks = 0
                }
            } else if (count == 0 && bestPayload.chapters.isNotEmpty() && pageFinishedOnce && tick >= 6) {
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
