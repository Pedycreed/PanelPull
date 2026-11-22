package com.example.ui.components

import org.json.JSONObject
import org.json.JSONTokener

data class ParsedWebViewPayload(
    val url: String,
    val title: String,
    val images: List<String>,
    val chapters: List<Pair<String, String>>,
    val source: String,
    val readyState: String
)

/**
 * Shared JS and network-sniffing helpers used by both the automatic HeadlessWebViewExtractor
 * and the interactive WebViewExtractorDialog.
 */
object WebViewJsScripts {

    private val imageExtRegex = Regex("""\.(?:jpg|jpeg|png|webp|avif|jfif)(?:\?|$)""", RegexOption.IGNORE_CASE)
    private val nonImageAssetRegex = Regex(
        """\.(?:js|mjs|css|html|htm|json|xml|svg|ico|woff2?|ttf|otf|eot|mp4|webm|m3u8|ts|mp3|ogg|map|txt)(?:\?|$)""",
        RegexOption.IGNORE_CASE
    )
    private val comicCdnHintRegex = Regex(
        """(wowpic|comix|cdn|img|image|media|storage|chapter|chap|pages|manga|manhwa|webtoon|comic)""",
        RegexOption.IGNORE_CASE
    )

    private val excludedUrlTokens = listOf(
        "favicon", "logo", "avatar", "banner", "advert", "ads.", "/ads/",
        "doubleclick", "googlesyndication", "googletagmanager", "google-analytics",
        "poster", "posters/", "cover", "covers/", "thumb", "thumbnail",
        "badge", "emoji", "icon", "sprite", "discord", "patreon", "ko-fi",
        "gravatar", "cloudflare", "captcha", "turnstile", "analytics", "tracker",
        "pixel", "1x1", "og-image", "twitter-card", "apple-touch-icon",
        "profile", "author", "spinner", "loading.gif", "placeholder"
    )

    fun isLikelySniffedComicImage(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (nonImageAssetRegex.containsMatchIn(lower)) return false
        if (excludedUrlTokens.any { lower.contains(it) }) return false

        if (imageExtRegex.containsMatchIn(lower)) return true

        // Some comic CDNs (e.g., wowpic / signed proxy routes) serve images without a file extension
        val looksLikeComicCdnPath = comicCdnHintRegex.containsMatchIn(lower) &&
            (lower.contains("/files/") || lower.contains("/chapter") || lower.contains("/pages/") ||
                lower.contains("wowpic") || lower.contains("/img/"))
        return looksLikeComicCdnPath
    }

    /**
     * Non-destructively hooks window.fetch and XMLHttpRequest so that even when a SPA (such as
     * comix.to) computes obfuscated/signed API tokens in client-side JS, we capture the resulting
     * chapter image lists and chapter index automatically.
     */
    fun buildNetworkHookScript(): String = """
        (function() {
          if (window.__PANELPULL_HOOKED) return;
          window.__PANELPULL_HOOKED = true;
          window.__PANELPULL_API_IMAGES = window.__PANELPULL_API_IMAGES || [];
          window.__PANELPULL_API_CHAPTERS = window.__PANELPULL_API_CHAPTERS || [];
          var seenImgs = {};
          var seenChs = {};

          function addApiImg(u) {
            if (!u || typeof u !== 'string') return;
            u = u.trim();
            if (!/^https?:\/\//i.test(u)) return;
            if (/\.(svg|ico|js|css|json|woff2?|ttf)(\?|$)/i.test(u)) return;
            if (/(favicon|logo|avatar|poster|posters\/|cover|covers\/|thumb|discord|patreon|ko-fi|gravatar|doubleclick|googlesyndication|badge|emoji|turnstile|captcha)/i.test(u)) return;
            if (!seenImgs[u]) {
              seenImgs[u] = true;
              window.__PANELPULL_API_IMAGES.push(u);
            }
          }

          function addApiChapter(text, href) {
            if (!href || typeof href !== 'string') return;
            href = href.trim();
            if (!href || seenChs[href]) return;
            seenChs[href] = true;
            window.__PANELPULL_API_CHAPTERS.push({ text: text || '', href: href });
          }

          function scanParsedObject(obj, depth) {
            if (!obj || depth > 8) return;
            if (Array.isArray(obj)) {
              for (var i = 0; i < obj.length; i++) {
                scanParsedObject(obj[i], depth + 1);
              }
              return;
            }
            if (typeof obj !== 'object') return;

            var imgKeys = ['images', 'pages', 'chapter_images', 'chapterImages', 'md_images', 'sources', 'files'];
            for (var k = 0; k < imgKeys.length; k++) {
              var arr = obj[imgKeys[k]];
              if (Array.isArray(arr) && arr.length > 0) {
                for (var j = 0; j < arr.length; j++) {
                  var item = arr[j];
                  if (typeof item === 'string') {
                    addApiImg(item);
                  } else if (item && typeof item === 'object') {
                    addApiImg(item.url || item.src || item.image || item.image_url || item.file || '');
                  }
                }
              }
            }

            if (obj.chapter_id !== undefined && obj.number !== undefined) {
              try {
                var pathParts = window.location.pathname.split('/').filter(Boolean);
                var tIdx = pathParts.indexOf('title');
                if (tIdx !== -1 && pathParts[tIdx + 1]) {
                  var numStr = String(obj.number).replace(/\.0$/, '');
                  var chHref = window.location.origin + '/title/' + pathParts[tIdx + 1] + '/' + obj.chapter_id + '-chapter-' + numStr;
                  addApiChapter('Capítulo ' + numStr, chHref);
                }
              } catch (e) {}
            }

            var keys = Object.keys(obj);
            for (var m = 0; m < keys.length; m++) {
              var val = obj[keys[m]];
              if (val && typeof val === 'object') {
                scanParsedObject(val, depth + 1);
              }
            }
          }

          window.__PANELPULL_SCAN_TEXT = function(txt) {
            if (!txt || typeof txt !== 'string' || txt.length < 20) return;
            var trimmed = txt.trim();
            if (trimmed[0] === '{' || trimmed[0] === '[') {
              try {
                scanParsedObject(JSON.parse(trimmed), 0);
                return;
              } catch (e) {}
            }
            if (txt.indexOf('images') !== -1 || txt.indexOf('pages') !== -1 || txt.indexOf('wowpic') !== -1) {
              var clean = txt
                .replace(/\\\\\"/g, '"')
                .replace(/\\\"/g, '"')
                .replace(/\\\//g, '/')
                .replace(/\\u0026/g, '&')
                .replace(/\\u003d/g, '=');
              var re = /"(?:images|pages|chapter_images|chapterImages|sources|files)"\s*:\s*\[/gi;
              var m;
              while ((m = re.exec(clean)) !== null) {
                var start = clean.indexOf('[', m.index);
                var d = 0, inS = false, esc = false, end = -1;
                for (var p = start; p < Math.min(clean.length, start + 250000); p++) {
                  var c = clean[p];
                  if (esc) { esc = false; continue; }
                  if (c === '\\' && inS) { esc = true; continue; }
                  if (c === '"') { inS = !inS; continue; }
                  if (!inS) {
                    if (c === '[') d++;
                    else if (c === ']') { d--; if (d === 0) { end = p + 1; break; } }
                  }
                }
                if (end > start) {
                  try {
                    scanParsedObject({ images: JSON.parse(clean.slice(start, end)) }, 0);
                  } catch (e) {}
                }
              }
            }
          };

          if (typeof window.fetch === 'function') {
            var origFetch = window.fetch;
            window.fetch = function() {
              var p = origFetch.apply(this, arguments);
              try {
                p.then(function(res) {
                  try {
                    if (!res || !res.clone) return;
                    var ct = (res.headers && res.headers.get && res.headers.get('content-type')) || '';
                    if (ct.indexOf('json') !== -1 || ct.indexOf('text') !== -1 || ct.indexOf('x-component') !== -1 || ct === '') {
                      res.clone().text().then(function(bodyText) {
                        window.__PANELPULL_SCAN_TEXT(bodyText);
                      }).catch(function() {});
                    }
                  } catch (e) {}
                }).catch(function() {});
              } catch (e) {}
              return p;
            };
          }

          if (window.XMLHttpRequest && window.XMLHttpRequest.prototype) {
            var origSend = window.XMLHttpRequest.prototype.send;
            window.XMLHttpRequest.prototype.send = function() {
              try {
                this.addEventListener('load', function() {
                  try {
                    if (this.responseType === '' || this.responseType === 'text') {
                      window.__PANELPULL_SCAN_TEXT(this.responseText);
                    }
                  } catch (e) {}
                });
              } catch (e) {}
              return origSend.apply(this, arguments);
            };
          }
        })();
    """.trimIndent()

    /**
     * Multi-layer DOM + API + Next.js App Router (__next_f) extraction script.
     * Priority:
     * 1) Intercepted fetch/XHR API JSON image arrays
     * 2) Stitched in-memory window.__next_f + __NEXT_DATA__ + concatenated <script> blocks
     * 3) Rendered DOM <img> elements (excluding nav/header/footer/aside/recommendations)
     * 4) Chapter links from API, __next_f, and DOM anchors
     */
    fun buildSmartExtractionScript(): String = """
        (function() {
          var seen = {};
          var ordered = [];
          var source = 'none';

          function addUrl(u, fromSource) {
            if (!u || typeof u !== 'string') return;
            u = u.trim();
            if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0 || /\.(svg|ico)(\?|$)/i.test(u)) return;
            if (/(favicon|logo|avatar|discord|patreon|ko-fi|gravatar|doubleclick|googlesyndication|badge|emoji|turnstile|captcha|analytics|og-image)/i.test(u)) return;
            if (u.indexOf('//') === 0) u = window.location.protocol + u;
            if (!seen[u]) {
              seen[u] = true;
              ordered.push(u);
              if (source === 'none' && fromSource) source = fromSource;
            }
          }

          function scanArrayInText(rawText, tag) {
            if (!rawText) return;
            var clean = rawText
              .replace(/\\\\\"/g, '"')
              .replace(/\\\"/g, '"')
              .replace(/\\\//g, '/')
              .replace(/\\u0026/g, '&')
              .replace(/\\u003d/g, '=')
              .replace(/\\u002F/gi, '/');
            var re = /"(?:images|pages|chapter_images|chapterImages|md_images|sources|files)"\s*:\s*\[/gi;
            var match;
            while ((match = re.exec(clean)) !== null) {
              var arrStart = clean.indexOf('[', match.index);
              var depth = 0, inStr = false, esc = false, arrEnd = -1;
              for (var k = arrStart; k < Math.min(clean.length, arrStart + 250000); k++) {
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
                      if (typeof item === 'string') addUrl(item, tag);
                      else if (item && typeof item === 'object') {
                        addUrl(item.url || item.src || item.image || item.image_url || item.file || '', tag);
                      }
                    });
                  }
                } catch (e) {}
              }
            }
          }

          // 1. Intercepted fetch/XHR API images
          try {
            if (Array.isArray(window.__PANELPULL_API_IMAGES) && window.__PANELPULL_API_IMAGES.length > 0) {
              window.__PANELPULL_API_IMAGES.forEach(function(u) { addUrl(u, 'api'); });
            }
          } catch (e) {}

          // 2. Stitched Next.js App Router window.__next_f + __NEXT_DATA__ + combined <script> tags
          var stitchedNextF = '';
          try {
            if (Array.isArray(window.__next_f)) {
              stitchedNextF = window.__next_f.map(function(chunk) {
                if (Array.isArray(chunk) && typeof chunk[1] === 'string') return chunk[1];
                if (typeof chunk === 'string') return chunk;
                return '';
              }).join('');
              if (stitchedNextF) scanArrayInText(stitchedNextF, 'next_f');
            }
          } catch (e) {}

          try {
            if (ordered.length < 2 && window.__NEXT_DATA__) {
              scanArrayInText(JSON.stringify(window.__NEXT_DATA__), 'next_f');
            }
          } catch (e) {}

          try {
            if (ordered.length < 2) {
              var scripts = Array.from(document.querySelectorAll('script'));
              var combinedScripts = '';
              for (var s = 0; s < scripts.length; s++) {
                var txt = scripts[s].textContent || '';
                if (!txt) continue;
                if (txt.indexOf('__next_f') !== -1 || txt.indexOf('images') !== -1 || txt.indexOf('pages') !== -1 || txt.indexOf('__NEXT_DATA__') !== -1 || txt.indexOf('ts_reader') !== -1) {
                  combinedScripts += '\n' + txt;
                }
              }
              if (combinedScripts) scanArrayInText(combinedScripts, 'next_f');
            }
          } catch (e) {}

          // 3. Rendered DOM <img> elements (prioritizing reader area, excluding chrome/sidebars/posters)
          if (ordered.length < 2) {
            try {
              var domImgs = Array.from(document.querySelectorAll('img')).map(function(img) {
                var srcset = img.getAttribute('data-srcset') || img.getAttribute('srcset') || '';
                var firstSrcSet = srcset ? srcset.split(',')[0].trim().split(' ')[0] : '';
                var src = img.currentSrc || img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') || img.getAttribute('data-url') || firstSrcSet || img.src || '';
                var rect = img.getBoundingClientRect();
                var inChrome = !!img.closest('header, footer, nav, aside, [class*="sidebar"], [class*="recommend"], [class*="related"], [class*="popular"], [class*="comment"]');
                var clsMeta = ((img.className || '') + ' ' + (img.id || '') + ' ' + (img.alt || '')).toLowerCase();
                var isCoverish = /(poster|cover|avatar|logo|banner|thumb)/.test(clsMeta) || /(poster|posters\/|cover|covers\/|thumb|avatar)/.test((src || '').toLowerCase());
                return {
                  src: src,
                  w: img.naturalWidth || rect.width || 0,
                  h: img.naturalHeight || rect.height || 0,
                  absTop: (rect.top || 0) + (window.scrollY || 0),
                  inChrome: inChrome,
                  isCoverish: isCoverish
                };
              }).filter(function(i) {
                return !i.inChrome && !i.isCoverish && i.src && !i.src.startsWith('data:image/svg') && (i.w === 0 || i.w >= 200 || i.h >= 200);
              }).sort(function(a, b) {
                return a.absTop - b.absTop;
              });

              domImgs.forEach(function(i) { addUrl(i.src, 'dom'); });
            } catch (e) {}
          }

          // 4. Detect chapter links from API hook, stitched __next_f, and DOM anchors
          var seenCh = {};
          var links = [];
          function addCh(text, href) {
            if (!href || typeof href !== 'string') return;
            href = href.trim();
            if (!href || href === window.location.href || seenCh[href]) return;
            seenCh[href] = true;
            links.push({ text: (text || '').trim(), href: href });
          }

          try {
            if (Array.isArray(window.__PANELPULL_API_CHAPTERS)) {
              window.__PANELPULL_API_CHAPTERS.forEach(function(c) { addCh(c.text, c.href); });
            }
          } catch (e) {}

          try {
            Array.from(document.querySelectorAll('a[href]')).forEach(function(a) {
              var text = (a.innerText || '').trim();
              var href = a.href || '';
              if (/(chapter|chap|cap[ií]tulo|cap|ep|ch)[-_\/\s#]*\d+/i.test(text + ' ' + href)) {
                addCh(text, href);
              }
            });
          } catch (e) {}

          try {
            if (links.length === 0 && stitchedNextF) {
              var pathParts = window.location.pathname.split('/').filter(Boolean);
              var tIdx = pathParts.indexOf('title');
              if (tIdx !== -1 && pathParts[tIdx + 1]) {
                var slug = pathParts[tIdx + 1];
                var chRe = /"chapter_id"\s*:\s*(\d+)[^}]{0,220}?"number"\s*:\s*"?(\d+(?:\.\d+)?)"?/g;
                var cm;
                while ((cm = chRe.exec(stitchedNextF)) !== null && links.length < 150) {
                  var chNum = cm[2].replace(/\.0$/, '');
                  addCh('Capítulo ' + chNum, window.location.origin + '/title/' + slug + '/' + cm[1] + '-chapter-' + chNum);
                }
              }
            }
          } catch (e) {}

          return JSON.stringify({
            url: window.location.href,
            title: document.title || '',
            images: ordered,
            chapters: links.slice(0, 150),
            source: source,
            readyState: document.readyState || 'loading'
          });
        })();
    """.trimIndent()

    fun parseExtractionJson(
        rawJson: String?,
        fallbackUrl: String,
        fallbackTitle: String,
        sniffedSnapshot: List<String>
    ): ParsedWebViewPayload {
        if (rawJson.isNullOrBlank() || rawJson == "null") {
            return ParsedWebViewPayload(
                url = fallbackUrl,
                title = fallbackTitle,
                images = sniffedSnapshot.distinct(),
                chapters = emptyList(),
                source = if (sniffedSnapshot.isNotEmpty()) "sniff" else "none",
                readyState = "unknown"
            )
        }

        val unquoted = try {
            if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                JSONTokener(rawJson).nextValue().toString()
            } else {
                rawJson
            }
        } catch (_: Exception) {
            rawJson
        }

        return try {
            val obj = JSONObject(unquoted)
            val url = obj.optString("url", fallbackUrl).ifBlank { fallbackUrl }
            val title = obj.optString("title", fallbackTitle).ifBlank { fallbackTitle }
            val source = obj.optString("source", "none")
            val readyState = obj.optString("readyState", "loading")

            val domImgs = mutableListOf<String>()
            val imgArr = obj.optJSONArray("images")
            if (imgArr != null) {
                for (i in 0 until imgArr.length()) {
                    val item = imgArr.optString(i)
                    if (item.isNotBlank()) domImgs.add(item)
                }
            }

            val mergedImgs = LinkedHashSet<String>()
            mergedImgs.addAll(domImgs)
            // If structured/DOM extraction found fewer than 3 pages, include passively sniffed CDN comic images
            if (mergedImgs.size < 3 && sniffedSnapshot.isNotEmpty()) {
                mergedImgs.addAll(sniffedSnapshot)
            }

            val chArr = obj.optJSONArray("chapters")
            val chapters = buildList {
                if (chArr != null) {
                    for (i in 0 until chArr.length()) {
                        val item = chArr.optJSONObject(i) ?: continue
                        val href = item.optString("href")
                        if (href.isNotBlank()) {
                            add(item.optString("text") to href)
                        }
                    }
                }
            }

            ParsedWebViewPayload(
                url = url,
                title = title,
                images = mergedImgs.toList(),
                chapters = chapters,
                source = if (domImgs.isEmpty() && mergedImgs.isNotEmpty()) "sniff" else source,
                readyState = readyState
            )
        } catch (_: Exception) {
            ParsedWebViewPayload(
                url = fallbackUrl,
                title = fallbackTitle,
                images = sniffedSnapshot.distinct(),
                chapters = emptyList(),
                source = if (sniffedSnapshot.isNotEmpty()) "sniff" else "none",
                readyState = "error"
            )
        }
    }
}
