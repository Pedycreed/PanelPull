package com.example.ui.components

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

data class ParsedWebViewPayload(
    val url: String,
    val title: String,
    val images: List<String>,
    val chapters: List<Pair<String, String>>,
    val source: String,
    val readyState: String,
    val atBottom: Boolean = false,
    val scrollProgressPercent: Int = 0,
    val domImageCount: Int = 0
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

    // Path-segment / boundary-aware exclusion so random CDN hashes or series slugs containing
    // substrings like "cover" or "pixel" are never falsely rejected.
    private val excludedUrlPathRegex = Regex(
        """(?:^|[/:._-])(favicon|logo|avatar|avatars|banner|banners|advert|ads|doubleclick|googlesyndication|googletagmanager|google-analytics|poster|posters|cover|covers|thumb|thumbs|thumbnail|thumbnails|badge|emoji|emojis|icon|icons|sprite|discord|patreon|ko-fi|gravatar|cloudflare|captcha|turnstile|analytics|tracker|1x1|og-image|twitter-card|apple-touch-icon|profile|author|spinner|loading|placeholder|blank)(?:[/:._?-]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun isLikelySniffedComicImage(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (nonImageAssetRegex.containsMatchIn(lower)) return false
        if (excludedUrlPathRegex.containsMatchIn(lower)) return false

        if (imageExtRegex.containsMatchIn(lower)) return true

        // Some comic CDNs (e.g., wowpic / signed proxy routes) serve images without a file extension
        val looksLikeComicCdnPath = comicCdnHintRegex.containsMatchIn(lower) &&
            (lower.contains("/files/") || lower.contains("/chapter") || lower.contains("/pages/") ||
                lower.contains("wowpic") || lower.contains("/img/"))
        return looksLikeComicCdnPath
    }

    /**
     * Non-destructively hooks window.fetch, XMLHttpRequest, and DOM scroll/mutations so that:
     * 1) Client-side signed API JSON payloads (e.g., comix.to) are captured intact.
     * 2) Virtualized or lazy-loaded comic readers that unmount offscreen <img> nodes still have
     *    every page recorded in reading order inside window.__PANELPULL_DOM_MAP.
     */
    fun buildNetworkHookScript(): String = """
        (function() {
          if (window.__PANELPULL_HOOKED) return;
          window.__PANELPULL_HOOKED = true;
          window.__PANELPULL_API_IMAGES = window.__PANELPULL_API_IMAGES || [];
          window.__PANELPULL_API_CHAPTERS = window.__PANELPULL_API_CHAPTERS || [];
          window.__PANELPULL_DOM_MAP = window.__PANELPULL_DOM_MAP || {};
          window.__PANELPULL_DOM_SEQ = window.__PANELPULL_DOM_SEQ || 0;
          var seenImgs = {};
          var seenChs = {};

          var EXCLUDE_PATH_RE = /(?:^|[\/:._-])(favicon|logo|avatar|avatars|discord|patreon|ko-fi|gravatar|doubleclick|googlesyndication|googletagmanager|google-analytics|badge|emoji|turnstile|captcha|analytics|og-image|twitter-card|apple-touch-icon|spinner|loading|placeholder|blank|1x1)(?:[\/:._?-]|$)/i;
          var COVER_PATH_RE = /(?:^|[\/:._-])(poster|posters|cover|covers|thumb|thumbs|thumbnail|thumbnails|avatar|avatars|logo|banner|banners)(?:[\/:._?-]|$)/i;

          function isPlaceholderUrl(u) {
            if (!u || typeof u !== 'string') return true;
            var s = u.trim();
            if (!s) return true;
            if (/^(data:|blob:|javascript:|about:)/i.test(s)) return true;
            if (/\.(svg|ico)(\?|$)/i.test(s)) return true;
            if (EXCLUDE_PATH_RE.test(s)) return true;
            return false;
          }

          function normalizeAbsUrl(u) {
            if (!u || typeof u !== 'string') return '';
            u = u.trim();
            if (!u) return '';
            if (u.indexOf('//') === 0) u = window.location.protocol + u;
            else if (u[0] === '/') u = window.location.origin + u;
            return u;
          }

          function addApiImg(u) {
            u = normalizeAbsUrl(u);
            if (!u || !/^https?:\/\//i.test(u)) return;
            if (/\.(svg|ico|js|css|json|woff2?|ttf|mp4|webm)(\?|$)/i.test(u)) return;
            if (EXCLUDE_PATH_RE.test(u) || COVER_PATH_RE.test(u)) return;
            if (!seenImgs[u]) {
              seenImgs[u] = true;
              window.__PANELPULL_API_IMAGES.push(u);
            }
          }

          function addApiChapter(text, href) {
            if (!href || typeof href !== 'string') return;
            href = normalizeAbsUrl(href);
            if (!href || seenChs[href]) return;
            seenChs[href] = true;
            window.__PANELPULL_API_CHAPTERS.push({ text: text || '', href: href });
          }

          function extractItemUrl(item) {
            if (!item) return '';
            if (typeof item === 'string') return item;
            if (typeof item === 'object') {
              return item.url || item.src || item.image || item.image_url || item.imageUrl || item.file || item.original || item.path || item.href || item.link || '';
            }
            return '';
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

            var imgKeys = ['images', 'pages', 'chapter_images', 'chapterImages', 'md_images', 'sources', 'files', 'list', 'data'];
            for (var k = 0; k < imgKeys.length; k++) {
              var arr = obj[imgKeys[k]];
              if (Array.isArray(arr) && arr.length > 0) {
                for (var j = 0; j < arr.length; j++) {
                  var u = extractItemUrl(arr[j]);
                  if (u && /^https?:\/\//i.test(u)) {
                    addApiImg(u);
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

          function extractRegexImageUrls(sliceText, callback) {
            if (!sliceText) return;
            var urlRe = /https?:\/\/[^"'\s<>\\{}()\[\]]+\.(?:jpg|jpeg|png|webp|avif|jfif)(?:\?[^"'\s<>\\{}()\[\]]*)?/gi;
            var um;
            while ((um = urlRe.exec(sliceText)) !== null) {
              callback(um[0].replace(/[\\,;]+$/, ''));
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
                .replace(/\\u003d/g, '=')
                .replace(/\\u002F/gi, '/');
              var re = /"(?:images|pages|chapter_images|chapterImages|sources|files)"\s*:\s*\[/gi;
              var m;
              while ((m = re.exec(clean)) !== null) {
                var start = clean.indexOf('[', m.index);
                var d = 0, inS = false, esc = false, end = -1;
                var limit = Math.min(clean.length, start + 2000000);
                for (var p = start; p < limit; p++) {
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
                  var slice = clean.slice(start, end);
                  try {
                    scanParsedObject({ images: JSON.parse(slice) }, 0);
                  } catch (e) {
                    extractRegexImageUrls(slice, addApiImg);
                  }
                }
              }
            }
          };

          function parseBestSrcSet(srcset) {
            if (!srcset || typeof srcset !== 'string') return '';
            var parts = srcset.split(',').map(function(s) { return s.trim(); }).filter(Boolean);
            if (parts.length === 0) return '';
            // Prefer the last (highest-res) or first valid entry
            for (var i = parts.length - 1; i >= 0; i--) {
              var candidate = parts[i].split(/\s+/)[0];
              if (!isPlaceholderUrl(candidate)) return candidate;
            }
            return '';
          }

          window.__PANELPULL_COLLECT_DOM = function() {
            var readerImgCount = 0;
            try {
              var imgs = Array.from(document.querySelectorAll('img'));
              for (var i = 0; i < imgs.length; i++) {
                var img = imgs[i];
                var inChrome = !!img.closest('header, footer, nav, aside, [class*="sidebar"], [class*="recommend"], [class*="related"], [class*="popular"], [class*="comment"]');
                if (inChrome) continue;

                var clsMeta = ((img.className || '') + ' ' + (img.id || '') + ' ' + (img.alt || '')).toLowerCase();
                if (/(?:^|[\s_-])(poster|cover|avatar|logo|banner|thumb|thumbnail|icon|badge|emoji)(?:[\s_-]|$)/.test(clsMeta)) continue;

                // CRITICAL: Inspect lazy-load attributes FIRST so unloaded pages below the fold
                // that hold data:image/gif or loading.gif in src/currentSrc still yield their real URL!
                var lazyCandidates = [
                  img.getAttribute('data-src'),
                  img.getAttribute('data-original'),
                  img.getAttribute('data-lazy-src'),
                  img.getAttribute('data-cfsrc'),
                  img.getAttribute('data-url'),
                  img.getAttribute('data-full-url'),
                  img.getAttribute('data-hi-res-src'),
                  img.getAttribute('data-image'),
                  parseBestSrcSet(img.getAttribute('data-srcset'))
                ];

                // Also inspect sibling <source> inside <picture>
                var parentPic = img.parentElement && img.parentElement.tagName === 'PICTURE' ? img.parentElement : null;
                if (parentPic) {
                  var sources = parentPic.querySelectorAll('source');
                  for (var si = 0; si < sources.length; si++) {
                    lazyCandidates.push(parseBestSrcSet(sources[si].getAttribute('data-srcset') || sources[si].getAttribute('srcset') || ''));
                  }
                }

                var hasLazyUrl = false;
                var chosen = '';
                for (var lc = 0; lc < lazyCandidates.length; lc++) {
                  var cand = normalizeAbsUrl(lazyCandidates[lc]);
                  if (cand && !isPlaceholderUrl(cand)) {
                    chosen = cand;
                    hasLazyUrl = true;
                    break;
                  }
                }

                if (!chosen) {
                  var directCandidates = [
                    parseBestSrcSet(img.getAttribute('srcset')),
                    img.currentSrc,
                    img.getAttribute('src'),
                    img.src
                  ];
                  for (var dc = 0; dc < directCandidates.length; dc++) {
                    var dCand = normalizeAbsUrl(directCandidates[dc]);
                    if (dCand && !isPlaceholderUrl(dCand)) {
                      chosen = dCand;
                      break;
                    }
                  }
                }

                if (!chosen) continue;
                if (COVER_PATH_RE.test(chosen)) continue;

                var rect = img.getBoundingClientRect();
                var natW = img.naturalWidth || 0;
                var natH = img.naturalHeight || 0;
                var renW = rect.width || 0;
                var renH = rect.height || 0;

                // Only reject tiny icons when the real image has actually finished decoding (not a lazy placeholder)
                if (!hasLazyUrl && img.complete && natW > 0 && natW < 180 && natH > 0 && natH < 180) continue;
                if (!hasLazyUrl && natW === 0 && renW > 0 && renW < 120 && renH > 0 && renH < 120) continue;

                readerImgCount++;
                var absTop = Math.round((rect.top || 0) + (window.scrollY || 0));
                if (!window.__PANELPULL_DOM_MAP[chosen]) {
                  window.__PANELPULL_DOM_SEQ += 1;
                  window.__PANELPULL_DOM_MAP[chosen] = {
                    url: chosen,
                    y: absTop,
                    seq: window.__PANELPULL_DOM_SEQ
                  };
                } else if (absTop > 0 && window.__PANELPULL_DOM_MAP[chosen].y <= 0) {
                  window.__PANELPULL_DOM_MAP[chosen].y = absTop;
                }
              }
            } catch (e) {}
            return readerImgCount;
          };

          // Capture virtualized / newly mounted pages continuously during scroll
          var scrollTimer = null;
          window.addEventListener('scroll', function() {
            if (scrollTimer) return;
            scrollTimer = setTimeout(function() {
              scrollTimer = null;
              window.__PANELPULL_COLLECT_DOM();
            }, 120);
          }, { passive: true });

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
     * Evaluates ALL layers (API hook, stitched __next_f / scripts, cumulative virtualized DOM)
     * without short-circuiting, then picks the richest set and returns scroll telemetry so
     * HeadlessWebViewExtractor can reliably sweep 100% of long-strip chapters.
     */
    fun buildSmartExtractionScript(): String = """
        (function() {
          var EXCLUDE_PATH_RE = /(?:^|[\/:._-])(favicon|logo|avatar|avatars|discord|patreon|ko-fi|gravatar|doubleclick|googlesyndication|googletagmanager|google-analytics|badge|emoji|turnstile|captcha|analytics|og-image|twitter-card|apple-touch-icon|spinner|loading|placeholder|blank|1x1)(?:[\/:._?-]|$)/i;
          var COVER_PATH_RE = /(?:^|[\/:._-])(poster|posters|cover|covers|thumb|thumbs|thumbnail|thumbnails|avatar|avatars|logo|banner|banners)(?:[\/:._?-]|$)/i;

          function normalizeUrl(u) {
            if (!u || typeof u !== 'string') return '';
            u = u.trim();
            if (!u || /^(data:|blob:|javascript:|about:)/i.test(u)) return '';
            if (/\.(svg|ico|js|css|json|woff2?|ttf|mp4|webm)(\?|$)/i.test(u)) return '';
            if (EXCLUDE_PATH_RE.test(u) || COVER_PATH_RE.test(u)) return '';
            if (u.indexOf('//') === 0) u = window.location.protocol + u;
            else if (u[0] === '/') u = window.location.origin + u;
            if (!/^https?:\/\//i.test(u)) return '';
            return u;
          }

          function createBucket() {
            var seen = {};
            var list = [];
            return {
              add: function(u) {
                var clean = normalizeUrl(u);
                if (clean && !seen[clean]) {
                  seen[clean] = true;
                  list.push(clean);
                }
              },
              list: list
            };
          }

          function extractRegexImageUrls(sliceText, bucket) {
            if (!sliceText) return;
            var urlRe = /https?:\/\/[^"'\s<>\\{}()\[\]]+\.(?:jpg|jpeg|png|webp|avif|jfif)(?:\?[^"'\s<>\\{}()\[\]]*)?/gi;
            var um;
            while ((um = urlRe.exec(sliceText)) !== null) {
              bucket.add(um[0].replace(/[\\,;]+$/, ''));
            }
          }

          function scanArrayInText(rawText, bucket) {
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
              var limit = Math.min(clean.length, arrStart + 2000000);
              for (var k = arrStart; k < limit; k++) {
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
                var slice = clean.slice(arrStart, arrEnd);
                try {
                  var parsed = JSON.parse(slice);
                  if (Array.isArray(parsed)) {
                    parsed.forEach(function(item) {
                      if (typeof item === 'string') bucket.add(item);
                      else if (item && typeof item === 'object') {
                        bucket.add(item.url || item.src || item.image || item.image_url || item.imageUrl || item.file || item.original || item.path || '');
                      }
                    });
                  }
                } catch (e) {
                  // Fallback to regex extraction inside the array slice if JSON.parse failed on RSC escaping
                  extractRegexImageUrls(slice, bucket);
                }
              }
            }
          }

          // 1. Intercepted fetch/XHR API images
          var apiBucket = createBucket();
          try {
            if (Array.isArray(window.__PANELPULL_API_IMAGES)) {
              window.__PANELPULL_API_IMAGES.forEach(function(u) { apiBucket.add(u); });
            }
          } catch (e) {}

          // 2. Stitched Next.js App Router window.__next_f + __NEXT_DATA__ + combined <script> tags
          var scriptBucket = createBucket();
          var stitchedNextF = '';
          try {
            if (Array.isArray(window.__next_f)) {
              stitchedNextF = window.__next_f.map(function(chunk) {
                if (Array.isArray(chunk) && typeof chunk[1] === 'string') return chunk[1];
                if (typeof chunk === 'string') return chunk;
                return '';
              }).join('');
              if (stitchedNextF) scanArrayInText(stitchedNextF, scriptBucket);
            }
          } catch (e) {}

          try {
            if (window.__NEXT_DATA__) {
              scanArrayInText(JSON.stringify(window.__NEXT_DATA__), scriptBucket);
            }
          } catch (e) {}

          try {
            var scripts = Array.from(document.querySelectorAll('script'));
            var combinedScripts = '';
            for (var s = 0; s < scripts.length; s++) {
              var txt = scripts[s].textContent || '';
              if (!txt) continue;
              if (txt.indexOf('__next_f') !== -1 || txt.indexOf('images') !== -1 || txt.indexOf('pages') !== -1 || txt.indexOf('__NEXT_DATA__') !== -1 || txt.indexOf('ts_reader') !== -1) {
                combinedScripts += '\n' + txt;
              }
            }
            if (combinedScripts) scanArrayInText(combinedScripts, scriptBucket);
          } catch (e) {}

          // 3. Cumulative DOM <img> elements (lazy-load + virtualization safe)
          var domImgCount = 0;
          if (typeof window.__PANELPULL_COLLECT_DOM === 'function') {
            domImgCount = window.__PANELPULL_COLLECT_DOM();
          }
          var domBucket = createBucket();
          try {
            var mapObj = window.__PANELPULL_DOM_MAP || {};
            var entries = Object.keys(mapObj).map(function(k) { return mapObj[k]; });
            entries.sort(function(a, b) {
              if (a.y !== b.y) return a.y - b.y;
              return a.seq - b.seq;
            });
            entries.forEach(function(item) { domBucket.add(item.url); });
          } catch (e) {}

          // Choose the richest authoritative source without truncating longer chapters
          var ordered = [];
          var source = 'none';
          var candidates = [
            { name: 'api', list: apiBucket.list },
            { name: 'next_f', list: scriptBucket.list },
            { name: 'dom', list: domBucket.list }
          ];
          candidates.sort(function(a, b) { return b.list.length - a.list.length; });
          if (candidates[0].list.length > 0) {
            source = candidates[0].name;
            ordered = candidates[0].list.slice();
            // If another layer captured extra pages from the same comic CDN host, merge them in order
            var primaryHosts = {};
            ordered.forEach(function(u) {
              try { primaryHosts[new URL(u).host] = true; } catch (e) {}
            });
            var seenFinal = {};
            ordered.forEach(function(u) { seenFinal[u] = true; });
            for (var ci = 1; ci < candidates.length; ci++) {
              var extraList = candidates[ci].list;
              for (var ei = 0; ei < extraList.length; ei++) {
                var eu = extraList[ei];
                if (seenFinal[eu]) continue;
                try {
                  if (primaryHosts[new URL(eu).host]) {
                    seenFinal[eu] = true;
                    ordered.push(eu);
                  }
                } catch (e) {}
              }
            }
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
                while ((cm = chRe.exec(stitchedNextF)) !== null && links.length < 300) {
                  var chNum = cm[2].replace(/\.0$/, '');
                  addCh('Capítulo ' + chNum, window.location.origin + '/title/' + slug + '/' + cm[1] + '-chapter-' + chNum);
                }
              }
            }
          } catch (e) {}

          var docEl = document.documentElement || document.body;
          var scrollH = Math.max(
            (docEl && docEl.scrollHeight) || 0,
            (document.body && document.body.scrollHeight) || 0
          );
          var winH = window.innerHeight || 844;
          var curY = window.scrollY || window.pageYOffset || 0;
          var maxScroll = Math.max(1, scrollH - winH);
          var scrollPct = Math.min(100, Math.round((curY / maxScroll) * 100));
          var atBottom = scrollH <= winH + 120 || (curY + winH >= scrollH - 120);

          return JSON.stringify({
            url: window.location.href,
            title: document.title || '',
            images: ordered,
            chapters: links.slice(0, 300),
            source: source,
            readyState: document.readyState || 'loading',
            atBottom: atBottom,
            scrollProgressPercent: scrollPct,
            domImageCount: Math.max(domImgCount, domBucket.list.length)
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
            val atBottom = obj.optBoolean("atBottom", false)
            val scrollProgressPercent = obj.optInt("scrollProgressPercent", 0)
            val domImageCount = obj.optInt("domImageCount", 0)

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
            if (sniffedSnapshot.isNotEmpty()) {
                if (mergedImgs.size < 3) {
                    mergedImgs.addAll(sniffedSnapshot)
                } else {
                    // Merge any additional sniffed pages from the SAME comic CDN host(s)
                    // (crucial for virtualized or lazily-unmounted webtoon readers)
                    val primaryHosts = domImgs.mapNotNull { hostOf(it) }.toSet()
                    if (primaryHosts.isNotEmpty()) {
                        sniffedSnapshot.forEach { sniffed ->
                            if (hostOf(sniffed) in primaryHosts) {
                                mergedImgs.add(sniffed)
                            }
                        }
                    }
                }
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
                readyState = readyState,
                atBottom = atBottom,
                scrollProgressPercent = scrollProgressPercent,
                domImageCount = domImageCount
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

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()
}
