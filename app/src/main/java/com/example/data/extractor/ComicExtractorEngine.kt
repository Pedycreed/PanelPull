package com.example.data.extractor

import android.net.Uri
import com.example.data.model.ComicFormat
import com.example.data.model.DetectedChapterLink
import com.example.data.model.ExtractedPageCandidate
import com.example.data.model.ExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ComicExtractorEngine(
    val okHttpClient: OkHttpClient = defaultHttpClient()
) {
    private val comixSiteExtractor = ComixSiteExtractor(okHttpClient)

    suspend fun extractFromUrl(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawUrl)

        // 1. Try dedicated API adapter for Comix.to-style sites first
        if (comixSiteExtractor.canHandle(normalizedUrl)) {
            val apiResult = comixSiteExtractor.extractViaApi(normalizedUrl)
            if (apiResult != null && (apiResult.pages.isNotEmpty() || apiResult.detectedChapters.isNotEmpty())) {
                return@withContext apiResult
            }
        }

        // 2. Standard HTML + Next.js RSC / __NEXT_DATA__ / Reader JSON extraction
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", WebSessionCookieStore.originOf(normalizedUrl) + "/")

        WebSessionCookieStore.getCookieForUrl(normalizedUrl)?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }

        val html = okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "HTTP ${response.code}: o site bloqueou o acesso direto ou requer JavaScript. Use o botão Navegador JS."
                )
            }
            response.body?.string().orEmpty()
        }

        if (html.isBlank()) {
            throw IOException("A página retornou HTML vazio. Tente o Modo Navegador JS.")
        }

        parseHtmlDocument(normalizedUrl, html)
    }

    fun parseHtmlDocument(sourceUrl: String, html: String): ExtractionResult {
        val doc = Jsoup.parse(html, sourceUrl)
        val siteHost = Uri.parse(sourceUrl).host.orEmpty().removePrefix("www.")
        val rawTitle = doc.title().ifBlank { siteHost }

        val candidates = collectImageCandidates(doc, sourceUrl)
        val chapterLinks = collectChapterLinks(doc, sourceUrl)
        val (seriesTitle, chapterNumber, chapterLabel) = inferMetadataFromUrlAndTitle(sourceUrl, rawTitle)
        val format = inferComicFormat(sourceUrl, rawTitle, siteHost)

        return ExtractionResult(
            sourceUrl = sourceUrl,
            siteHost = siteHost,
            seriesTitle = seriesTitle,
            chapterLabel = chapterLabel,
            chapterNumber = chapterNumber,
            comicFormat = format,
            pages = candidates,
            detectedChapters = chapterLinks,
            usedAiFilter = false
        )
    }

    fun buildResultFromWebViewExtraction(
        sourceUrl: String,
        pageTitle: String,
        imageUrls: List<String>,
        chapterLinks: List<Pair<String, String>>
    ): ExtractionResult {
        val normalizedUrl = normalizeUrl(sourceUrl)
        val siteHost = Uri.parse(normalizedUrl).host.orEmpty().removePrefix("www.")
        val filtered = imageUrls
            .mapNotNull { resolveAbsoluteUrl(normalizedUrl, it) }
            .filter { isLikelyComicImageUrl(it) }
            .distinct()
            .mapIndexed { index, url ->
                ExtractedPageCandidate(
                    index = index,
                    imageUrl = url,
                    refererUrl = normalizedUrl
                )
            }

        val chapters = chapterLinks.mapNotNull { (title, href) ->
            val abs = resolveAbsoluteUrl(normalizedUrl, href) ?: return@mapNotNull null
            if (abs == normalizedUrl) return@mapNotNull null
            val num = extractChapterNumber(title, abs) ?: return@mapNotNull null
            DetectedChapterLink(
                title = title.trim().ifBlank { "Capítulo $num" },
                chapterNumber = num,
                url = abs
            )
        }.distinctBy { it.url }
            .sortedBy { it.chapterNumber.toDoubleOrNull() ?: Double.MAX_VALUE }

        val (seriesTitle, chapterNumber, chapterLabel) = inferMetadataFromUrlAndTitle(
            normalizedUrl,
            pageTitle.ifBlank { siteHost }
        )

        return ExtractionResult(
            sourceUrl = normalizedUrl,
            siteHost = siteHost,
            seriesTitle = seriesTitle,
            chapterLabel = chapterLabel,
            chapterNumber = chapterNumber,
            comicFormat = inferComicFormat(normalizedUrl, pageTitle, siteHost),
            pages = filtered,
            detectedChapters = chapters,
            usedAiFilter = false
        )
    }

    private fun collectImageCandidates(doc: Document, baseUrl: String): List<ExtractedPageCandidate> {
        val ordered = LinkedHashMap<String, ExtractedPageCandidate>()

        // 1. Prioritize explicit comic reader DOM containers when present
        val readerContainers = doc.select(
            ".reading-content, #readerarea, .reader-area, .chapter-content, " +
                ".viewer_lst, #_imageList, .container-chapter-reader, " +
                "[class*=chapter-img], [class*=page-break], [id*=reader], " +
                "[class*=chapter-reader], [class*=comic-pages], [class*=read-container]"
        )
        if (readerContainers.isNotEmpty()) {
            collectFromImgElements(readerContainers.select("img, picture source"), baseUrl, ordered)
        }

        // 2. Inspect Next.js App Router (self.__next_f.push), __NEXT_DATA__, and reader script JSON payloads.
        // Always run this if explicit reader containers did not already provide a chapter set,
        // because SPA sites like comix.to embed chapter images inside script JSON while sidebar <img> are unrelated covers.
        if (ordered.size < 2) {
            val scriptCandidates = collectFromEmbeddedScripts(doc, baseUrl)
            if (scriptCandidates.isNotEmpty()) {
                return scriptCandidates
            }
        }

        // 3. Fallback: inspect main/article/body <img> elements excluding header/nav/footer/sidebar recommendations
        if (ordered.isEmpty()) {
            val mainContent = doc.select("main, article, [role=main]")
            val scope = if (mainContent.isNotEmpty()) mainContent else doc.select("body")
            val filteredImgs = scope.select("img, picture source").filterNot { el ->
                el.parents().any { parent ->
                    val tag = parent.tagName().lowercase()
                    val cls = "${parent.className()} ${parent.id()}".lowercase()
                    tag in setOf("header", "footer", "nav", "aside") ||
                        cls.contains("sidebar") ||
                        cls.contains("recommend") ||
                        cls.contains("related") ||
                        cls.contains("popular") ||
                        cls.contains("comment")
                }
            }
            collectFromImgElements(filteredImgs, baseUrl, ordered)
        }

        // 4. Last-resort fallback: any valid image on page
        if (ordered.isEmpty()) {
            collectFromImgElements(doc.select("img"), baseUrl, ordered)
        }

        return ordered.values.toList()
    }

    private fun collectFromImgElements(
        elements: Iterable<org.jsoup.nodes.Element>,
        baseUrl: String,
        ordered: LinkedHashMap<String, ExtractedPageCandidate>
    ) {
        for (el in elements) {
            val rawSrc = listOf(
                el.attr("data-src"),
                el.attr("data-original"),
                el.attr("data-lazy-src"),
                el.attr("data-cfsrc"),
                el.attr("data-url"),
                el.attr("data-full-url"),
                parseSrcSetFirst(el.attr("data-srcset")),
                parseSrcSetFirst(el.attr("srcset")),
                el.attr("src")
            ).firstOrNull { it.isNotBlank() && !it.startsWith("data:image/svg") }
                ?: continue

            val absUrl = resolveAbsoluteUrl(baseUrl, rawSrc) ?: continue
            val alt = el.attr("alt")
            val cssMeta = "${el.className()} ${el.id()} $alt".lowercase()
            if (isNoiseElement(cssMeta) || !isLikelyComicImageUrl(absUrl)) continue

            val widthHint = el.attr("width").toIntOrNull() ?: 0
            val heightHint = el.attr("height").toIntOrNull() ?: 0
            if (widthHint in 1..119 && heightHint in 1..119) continue

            if (!ordered.containsKey(absUrl)) {
                ordered[absUrl] = ExtractedPageCandidate(
                    index = ordered.size,
                    imageUrl = absUrl,
                    refererUrl = baseUrl,
                    altText = alt,
                    widthHint = widthHint,
                    heightHint = heightHint
                )
            }
        }
    }

    /**
     * Parses embedded JSON image lists used by Next.js App Router (`self.__next_f.push`),
     * `__NEXT_DATA__`, `ts_reader`, and similar modern manga/manhwa/webtoon frameworks.
     */
    private fun collectFromEmbeddedScripts(doc: Document, baseUrl: String): List<ExtractedPageCandidate> {
        val ordered = LinkedHashMap<String, ExtractedPageCandidate>()

        val stitchedNextF = buildStitchedNextFPayload(doc)
        val scriptPayloads = buildList {
            if (stitchedNextF.isNotBlank()) add(stitchedNextF)
            doc.selectFirst("script#__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() }?.let { add(it) }
            doc.select("script").forEach { script ->
                val data = script.data()
                if (data.isBlank()) return@forEach
                if (data.contains("__next_f", ignoreCase = true) ||
                    data.contains("\"images\"", ignoreCase = true) ||
                    data.contains("\\\"images\\\"", ignoreCase = true) ||
                    data.contains("\"pages\"", ignoreCase = true) ||
                    data.contains("\\\"pages\\\"", ignoreCase = true) ||
                    data.contains("ts_reader", ignoreCase = true) ||
                    data.contains("chapter_images", ignoreCase = true) ||
                    data.contains("chapterImages", ignoreCase = true)
                ) {
                    add(data)
                }
            }
        }

        // Pattern A: Explicit JSON arrays named "images", "pages", "chapter_images", "chapterImages", "sources", "files"
        val arrayKeyRegex = Regex(
            """"(?:images|pages|chapter_images|chapterImages|md_images|sources|files)"\s*:\s*\[""",
            RegexOption.IGNORE_CASE
        )

        for (rawScript in scriptPayloads) {
            val normalized = unescapeEmbeddedJson(rawScript)
            arrayKeyRegex.findAll(normalized).forEach { match ->
                val bracketStart = match.range.last // index of '['
                val arraySlice = extractBalancedBracketSlice(normalized, bracketStart) ?: return@forEach
                try {
                    val jsonArray = JSONArray(arraySlice)
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.opt(i) ?: continue
                        var candidateUrl: String? = null
                        var w = 0
                        var h = 0
                        when (item) {
                            is JSONObject -> {
                                candidateUrl = item.optString("url")
                                    .ifBlank { item.optString("src") }
                                    .ifBlank { item.optString("image") }
                                    .ifBlank { item.optString("image_url") }
                                    .ifBlank { item.optString("file") }
                                w = item.optInt("width", item.optInt("w", 0))
                                h = item.optInt("height", item.optInt("h", 0))
                            }
                            is String -> {
                                candidateUrl = item
                            }
                        }
                        val abs = candidateUrl?.let { resolveAbsoluteUrl(baseUrl, it) } ?: continue
                        if (!isLikelyComicImageUrl(abs)) continue
                        if (w in 1..119 && h in 1..119) continue
                        if (!ordered.containsKey(abs)) {
                            ordered[abs] = ExtractedPageCandidate(
                                index = ordered.size,
                                imageUrl = abs,
                                refererUrl = baseUrl,
                                widthHint = w,
                                heightHint = h
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Fallback to regex URL extraction inside this array slice
                    extractImageUrlsFromText(arraySlice, baseUrl, ordered)
                }
            }
        }

        if (ordered.isNotEmpty()) {
            return ordered.values.toList()
        }

        // Pattern B: Direct image URLs inside reader scripts (excluding common cover/poster keys)
        for (rawScript in scriptPayloads) {
            val normalized = unescapeEmbeddedJson(rawScript)
            extractImageUrlsFromText(normalized, baseUrl, ordered)
        }

        return ordered.values.toList()
    }

    private fun extractImageUrlsFromText(
        text: String,
        baseUrl: String,
        ordered: LinkedHashMap<String, ExtractedPageCandidate>
    ) {
        val urlRegex = Regex(
            """https?://[^"'\s<>\\{}()\[\]]+\.(?:jpg|jpeg|png|webp|avif|jfif)(?:\?[^"'\s<>\\{}()\[\]]*)?""",
            RegexOption.IGNORE_CASE
        )
        urlRegex.findAll(text).forEach { match ->
            val cleaned = match.value.trimEnd('\\', ',', ';')
            val absUrl = resolveAbsoluteUrl(baseUrl, cleaned) ?: return@forEach
            if (isLikelyComicImageUrl(absUrl) && !isLikelyPosterOrCoverUrl(absUrl) && !ordered.containsKey(absUrl)) {
                ordered[absUrl] = ExtractedPageCandidate(
                    index = ordered.size,
                    imageUrl = absUrl,
                    refererUrl = baseUrl
                )
            }
        }
    }

    /**
     * Concatenates string chunks pushed into Next.js App Router's `self.__next_f.push([1, "..."])`
     * across multiple <script> tags so JSON payloads split across chunks can be parsed intact.
     */
    private fun buildStitchedNextFPayload(doc: Document): String {
        val sb = StringBuilder()
        val pushRegex = Regex("""__next_f\.push\(\s*\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*\]\s*\)""")
        doc.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("__next_f")) {
                pushRegex.findAll(data).forEach { m ->
                    sb.append(m.groupValues[1])
                }
            }
        }
        return sb.toString()
    }

    private fun unescapeEmbeddedJson(raw: String): String {
        return raw
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
    }

    private fun extractBalancedBracketSlice(text: String, openBracketIndex: Int): String? {
        if (openBracketIndex !in text.indices || text[openBracketIndex] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        val maxEnd = (openBracketIndex + 250_000).coerceAtMost(text.length)
        for (i in openBracketIndex until maxEnd) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '[') depth++
                else if (ch == ']') {
                    depth--
                    if (depth == 0) {
                        return text.substring(openBracketIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun collectChapterLinks(doc: Document, baseUrl: String): List<DetectedChapterLink> {
        val chapterAnchorRegex = Regex(
            """(?i)(chapter|chap|cap[ií]tulo|cap|epis[oó]dio|ep|ch)[-_/.\s]*(\d+(?:\.\d+)?)"""
        )
        val links = LinkedHashMap<String, DetectedChapterLink>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { resolveAbsoluteUrl(baseUrl, a.attr("href")).orEmpty() }
            if (href.isBlank() || href == baseUrl) continue
            val text = a.text().trim()
            val match = chapterAnchorRegex.find(text) ?: chapterAnchorRegex.find(href) ?: continue
            val chNum = match.groupValues.getOrNull(2).orEmpty()
            if (chNum.isBlank()) continue
            if (!links.containsKey(href)) {
                links[href] = DetectedChapterLink(
                    title = text.ifBlank { "Capítulo $chNum" },
                    chapterNumber = chNum,
                    url = href
                )
            }
        }

        // Also look for Comix-style chapter objects embedded in Next.js scripts (stitched + individual)
        if (links.isEmpty()) {
            val comixParts = comixSiteExtractor.parseComixUrl(baseUrl)
            if (comixParts != null) {
                val baseOrigin = "https://${comixParts.host}"
                val chObjRegex = Regex("""\"chapter_id\"\s*:\s*(\d+)[^\}]{0,200}?\"number\"\s*:\s*\"?(\d+(?:\.\d+)?)\"?""")
                val candidatesToScan = buildList {
                    val stitched = buildStitchedNextFPayload(doc)
                    if (stitched.isNotBlank()) add(stitched)
                    doc.select("script").forEach { add(it.data()) }
                }
                candidatesToScan.forEach { raw ->
                    val normalized = unescapeEmbeddedJson(raw)
                    chObjRegex.findAll(normalized).forEach { m ->
                        val chId = m.groupValues[1]
                        val chNum = m.groupValues[2].removeSuffix(".0")
                        val href = "$baseOrigin/title/${comixParts.seriesSlug}/$chId-chapter-$chNum"
                        if (href != baseUrl && !links.containsKey(href)) {
                            links[href] = DetectedChapterLink(
                                title = "Capítulo $chNum",
                                chapterNumber = chNum,
                                url = href
                            )
                        }
                    }
                }
            }
        }

        return links.values
            .sortedBy { it.chapterNumber.toDoubleOrNull() ?: Double.MAX_VALUE }
            .take(150)
    }

    private fun isNoiseElement(meta: String): Boolean {
        val noiseTokens = listOf(
            "avatar", "logo", "favicon", "banner", "advert", "sponsor",
            "discord", "patreon", "kofi", "ko-fi", "paypal", "icon",
            "emoji", "reaction", "thumb-small", "profile", "author"
        )
        return noiseTokens.any { meta.contains(it) }
    }

    private fun isLikelyPosterOrCoverUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/poster") ||
            lower.contains("/posters/") ||
            lower.contains("/cover") ||
            lower.contains("/covers/") ||
            lower.contains("/thumb") ||
            lower.contains("/avatar")
    }

    private fun isLikelyComicImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.endsWith(".svg") || lower.contains(".svg?")) return false
        if (lower.endsWith(".ico") || lower.contains(".ico?")) return false
        if (lower.contains("1x1") || lower.contains("pixel") || lower.contains("tracking")) return false
        val noiseUrls = listOf(
            "favicon", "logo", "avatar", "discord", "patreon", "ko-fi",
            "gravatar", "ads.", "/ads/", "doubleclick", "googlesyndication",
            "spinner", "loading.gif", "placeholder", "badge", "emoji",
            "apple-touch-icon", "og-image", "twitter-card"
        )
        if (noiseUrls.any { lower.contains(it) }) return false
        return true
    }

    private fun parseSrcSetFirst(srcSet: String): String {
        if (srcSet.isBlank()) return ""
        return srcSet.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull().orEmpty()
    }

    private fun resolveAbsoluteUrl(baseUrl: String, candidate: String): String? {
        val trimmed = candidate.trim()
        if (trimmed.isBlank() || trimmed.startsWith("data:") || trimmed.startsWith("javascript:")) {
            return null
        }
        return try {
            val resolved = URI(baseUrl).resolve(trimmed).toString()
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    private fun inferMetadataFromUrlAndTitle(url: String, rawTitle: String): Triple<String, String, String> {
        val chNum = extractChapterNumber(rawTitle, url) ?: "001"
        val cleanTitle = rawTitle
            .replace(Regex("""\s*[-|•–—]\s*(Read|Ler|Online|Manga|Manhwa|Webtoon|Free|Scan|Comix(\.to)?|Comick).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)^Read\s+"""), "")
            .replace(Regex("""(?i)\b(cap[ií]tulo|chapter|chap|cap|ep|epis[oó]dio)\s*#?\s*\d+(?:\.\d+)?.*$"""), "")
            .trim()
            .trimEnd('-', '|', '•', '–', '—', ':', ' ')

        val urlSlugTitle = extractSeriesTitleFromUrlPath(url)
        val hostFallback = Uri.parse(url).host.orEmpty().removePrefix("www.")
        val seriesTitle = when {
            cleanTitle.isNotBlank() && !cleanTitle.equals(hostFallback, ignoreCase = true) && !cleanTitle.equals("comix", ignoreCase = true) -> cleanTitle
            !urlSlugTitle.isNullOrBlank() -> urlSlugTitle
            else -> hostFallback
        }
        val chapterLabel = "Capítulo $chNum"
        return Triple(seriesTitle, chNum, chapterLabel)
    }

    private fun extractSeriesTitleFromUrlPath(url: String): String? {
        val comixParts = comixSiteExtractor.parseComixUrl(url)
        if (comixParts != null && comixParts.readableTitle.isNotBlank()) {
            return comixParts.readableTitle
        }
        val pathSegments = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .split("/")
            .filter { it.isNotBlank() }
        val markerIdx = pathSegments.indexOfFirst {
            it.lowercase() in setOf("title", "manga", "manhwa", "comic", "series", "webtoon", "obra", "read")
        }
        val candidateSlug = if (markerIdx != -1 && markerIdx + 1 < pathSegments.size) {
            pathSegments[markerIdx + 1]
        } else {
            pathSegments.firstOrNull { !it.contains("chapter", ignoreCase = true) && !it.all { ch -> ch.isDigit() } }
        } ?: return null

        // Strip leading short hash prefix like "vlxz-" if followed by a multi-word slug
        val withoutPrefix = candidateSlug.replace(Regex("""^[a-zA-Z0-9]{3,8}-(?=[a-zA-Z]+-)"""), "")
        return withoutPrefix
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            .takeIf { it.isNotBlank() }
    }

    private fun extractChapterNumber(title: String, url: String): String? {
        val regex = Regex("""(?i)(?:cap[ií]tulo|chapter|chap|cap|ep|ch)[-_/.\s#]*(\d+(?:\.\d+)?)""")
        return regex.find(title)?.groupValues?.getOrNull(1)
            ?: regex.find(url)?.groupValues?.getOrNull(1)
    }

    private fun inferComicFormat(url: String, title: String, host: String): ComicFormat {
        val combined = "$url $title $host".lowercase()
        return when {
            combined.contains("webtoon") || combined.contains("webtoons") || combined.contains("tapas") -> ComicFormat.WEBTOON
            combined.contains("manga") || combined.contains("mangadex") -> ComicFormat.MANGA
            else -> ComicFormat.MANHWA
        }
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
