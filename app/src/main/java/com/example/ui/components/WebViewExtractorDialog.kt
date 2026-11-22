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
import org.json.JSONObject
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

    LaunchedEffect(isAutoScrolling) {
        while (isAutoScrolling) {
            webViewRef?.evaluateJavascript(
                "(function(){ window.scrollBy({top: Math.max(window.innerHeight * 1.4, 900), behavior: 'smooth'}); })();",
                null
            )
            delay(750L)
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
                            val js = buildSmartExtractionScript()
                            webViewRef?.evaluateJavascript(js) { rawJson ->
                                val sniffedSnapshot = synchronized(sniffedImageUrls) {
                                    sniffedImageUrls.toList()
                                }
                                if (rawJson == null || rawJson == "null") {
                                    if (sniffedSnapshot.isNotEmpty()) {
                                        onExtracted(currentUrl, currentTitle, sniffedSnapshot, emptyList())
                                    }
                                    return@evaluateJavascript
                                }
                                val unquoted = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                                    org.json.JSONTokener(rawJson).nextValue().toString()
                                } else {
                                    rawJson
                                }
                                try {
                                    val obj = JSONObject(unquoted)
                                    val url = obj.optString("url", currentUrl)
                                    val title = obj.optString("title", currentTitle)
                                    WebSessionCookieStore.syncFromWebView(url)

                                    val domImgs = mutableListOf<String>()
                                    val imgArr = obj.optJSONArray("images")
                                    if (imgArr != null) {
                                        for (i in 0 until imgArr.length()) {
                                            domImgs.add(imgArr.getString(i))
                                        }
                                    }

                                    // Prefer structured DOM/Next.js images; merge network-sniffed images if needed
                                    val mergedImgs = LinkedHashSet<String>()
                                    mergedImgs.addAll(domImgs)
                                    if (mergedImgs.size < 3) {
                                        mergedImgs.addAll(sniffedSnapshot)
                                    }

                                    val chArr = obj.optJSONArray("chapters")
                                    val chapters = buildList {
                                        if (chArr != null) {
                                            for (i in 0 until chArr.length()) {
                                                val item = chArr.getJSONObject(i)
                                                add(item.optString("text") to item.optString("href"))
                                            }
                                        }
                                    }
                                    onExtracted(url, title, mergedImgs.toList(), chapters)
                                } catch (_: Exception) {
                                    if (sniffedSnapshot.isNotEmpty()) {
                                        onExtracted(currentUrl, currentTitle, sniffedSnapshot, emptyList())
                                    }
                                }
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
                        settings.loadsImagesAutomatically = true
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
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) {
                                    WebSessionCookieStore.syncFromWebView(url)
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (isLikelySniffedComicImage(reqUrl)) {
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

private fun isLikelySniffedComicImage(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val hasImageExt = Regex("""\.(?:jpg|jpeg|png|webp|avif|jfif)(?:\?|$)""").containsMatchIn(lower)
    if (!hasImageExt) return false

    val excluded = listOf(
        "favicon", "logo", "avatar", "banner", "advert", "ads.", "/ads/",
        "doubleclick", "googlesyndication", "poster", "posters/", "cover",
        "covers/", "thumb", "badge", "emoji", "icon", "sprite", "discord",
        "patreon", "ko-fi", "gravatar", "cloudflare", "captcha"
    )
    return excluded.none { lower.contains(it) }
}

private fun buildSmartExtractionScript(): String = """
    (function() {
      var seen = {};
      var ordered = [];
      function addUrl(u) {
        if (!u || typeof u !== 'string') return;
        u = u.trim();
        if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0 || /\.svg(\?|$)/i.test(u)) return;
        if (/(favicon|logo|avatar|discord|patreon|ko-fi|gravatar|doubleclick|googlesyndication|badge|emoji)/i.test(u)) return;
        if (!seen[u]) {
          seen[u] = true;
          ordered.push(u);
        }
      }

      // 1. Try Next.js App Router (__next_f) & __NEXT_DATA__ embedded chapter image arrays first
      try {
        var scripts = Array.from(document.querySelectorAll('script'));
        for (var s = 0; s < scripts.length; s++) {
          var txt = scripts[s].textContent || '';
          if (!txt || (txt.indexOf('__next_f') === -1 && txt.indexOf('images') === -1 && txt.indexOf('__NEXT_DATA__') === -1)) continue;
          var clean = txt
            .replace(/\\\\\"/g, '"')
            .replace(/\\\"/g, '"')
            .replace(/\\\//g, '/')
            .replace(/\\u0026/g, '&')
            .replace(/\\u003d/g, '=');
          var matchIdx = clean.search(/"(images|pages|chapter_images|chapterImages)"\s*:\s*\[/);
          if (matchIdx !== -1) {
            var arrStart = clean.indexOf('[', matchIdx);
            var depth = 0, inStr = false, esc = false, arrEnd = -1;
            for (var k = arrStart; k < Math.min(clean.length, arrStart + 200000); k++) {
              var c = clean[k];
              if (esc) { esc = false; continue; }
              if (c === '\\' && inStr) { esc = true; continue; }
              if (c === '"') { inStr = !inStr; continue; }
              if (!inStr) {
                if (c === '[') depth++;
                else if (c === ']') { depth--; if (depth === 0) { arrEnd = k + 1; break; } }
              }
            }
            if (arrEnd > arrStart) {
              try {
                var parsed = JSON.parse(clean.slice(arrStart, arrEnd));
                if (Array.isArray(parsed)) {
                  parsed.forEach(function(item) {
                    if (typeof item === 'string') addUrl(item);
                    else if (item && typeof item === 'object') addUrl(item.url || item.src || item.image || '');
                  });
                }
              } catch (e) {}
            }
          }
        }
      } catch (e) {}

      // 2. Collect rendered DOM <img> elements (prioritizing reader area and excluding nav/header/footer/aside)
      var domImgs = Array.from(document.querySelectorAll('img')).map(function(img) {
        var src = img.currentSrc || img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') || img.src || '';
        var rect = img.getBoundingClientRect();
        var inChrome = !!img.closest('header, footer, nav, aside, [class*="sidebar"], [class*="recommend"], [class*="related"]');
        return {
          src: src,
          w: img.naturalWidth || rect.width || 0,
          h: img.naturalHeight || rect.height || 0,
          absTop: (rect.top || 0) + (window.scrollY || 0),
          inChrome: inChrome
        };
      }).filter(function(i) {
        return !i.inChrome && i.src && !i.src.startsWith('data:image/svg') && (i.w === 0 || i.w >= 220 || i.h >= 220);
      }).sort(function(a, b) {
        return a.absTop - b.absTop;
      });

      if (ordered.length < 2) {
        domImgs.forEach(function(i) { addUrl(i.src); });
      }

      // 3. Detect chapter links for batch download
      var links = Array.from(document.querySelectorAll('a[href]')).map(function(a) {
        return { text: (a.innerText || '').trim(), href: a.href || '' };
      }).filter(function(l) {
        return /(chapter|chap|cap[ií]tulo|cap|ep|ch)[-_\/\s#]*\d+/i.test(l.text + ' ' + l.href);
      }).slice(0, 150);

      return JSON.stringify({
        url: window.location.href,
        title: document.title || '',
        images: ordered,
        chapters: links
      });
    })();
""".trimIndent()
