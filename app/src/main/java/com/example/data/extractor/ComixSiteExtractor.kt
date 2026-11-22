package com.example.data.extractor

import com.example.data.model.ComicFormat
import com.example.data.model.DetectedChapterLink
import com.example.data.model.ExtractedPageCandidate
import com.example.data.model.ExtractionResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ComixUrlParts(
    val host: String,
    val hashId: String,
    val seriesSlug: String,
    val readableTitle: String,
    val chapterId: String?,
    val chapterNumber: String?
)

/**
 * Dedicated adapter for Comix.to (and compatible /title/{hash}-{slug}/{chapterId}-chapter-{num} endpoints).
 * Uses the site's JSON API v2 first to obtain clean chapter image arrays and full chapter lists,
 * and falls back gracefully to Next.js RSC/HTML extraction if needed.
 */
class ComixSiteExtractor(
    private val okHttpClient: OkHttpClient
) {
    fun canHandle(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "comix.to" || host.endsWith(".comix.to")
    }

    fun parseComixUrl(url: String): ComixUrlParts? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }
        val titleIdx = segments.indexOfFirst { it.equals("title", ignoreCase = true) }
        if (titleIdx == -1 || titleIdx + 1 >= segments.size) return null

        val seriesSegment = segments[titleIdx + 1]
        val dashIdx = seriesSegment.indexOf('-')
        val hashId = if (dashIdx > 0) seriesSegment.substring(0, dashIdx) else seriesSegment
        val rawSlug = if (dashIdx > 0) seriesSegment.substring(dashIdx + 1) else seriesSegment

        val chapterSegment = segments.getOrNull(titleIdx + 2)
        val chapterId = chapterSegment?.let { seg ->
            Regex("""^(\d+)""").find(seg)?.groupValues?.getOrNull(1)
        }
        val chapterNum = chapterSegment?.let { seg ->
            Regex("""(?i)(?:chapter|chap|cap|ch)[-_.\s]*(\d+(?:\.\d+)?)""")
                .find(seg)?.groupValues?.getOrNull(1)
        }

        val readableTitle = rawSlug
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            .ifBlank { hashId }

        return ComixUrlParts(
            host = host,
            hashId = hashId,
            seriesSlug = seriesSegment,
            readableTitle = readableTitle,
            chapterId = chapterId,
            chapterNumber = chapterNum
        )
    }

    fun extractViaApi(sourceUrl: String): ExtractionResult? {
        val parts = parseComixUrl(sourceUrl) ?: return null
        val baseOrigin = "https://${parts.host}"

        // 1. Fetch series details (title, comic format)
        var seriesTitle = parts.readableTitle
        var comicFormat = ComicFormat.MANHWA
        val mangaJson = fetchJson("$baseOrigin/api/v2/manga/${parts.hashId}", sourceUrl)
        if (mangaJson != null) {
            val resultObj = mangaJson.optJSONObject("result") ?: mangaJson.optJSONObject("data") ?: mangaJson
            val apiTitle = resultObj.optString("title").ifBlank { resultObj.optString("name") }
            if (apiTitle.isNotBlank()) {
                seriesTitle = apiTitle
            }
            val typeStr = resultObj.optString("type").lowercase()
            comicFormat = when {
                typeStr.contains("manga") -> ComicFormat.MANGA
                typeStr.contains("webtoon") -> ComicFormat.WEBTOON
                else -> ComicFormat.MANHWA
            }
        }

        // 2. Fetch chapter pages if a chapter ID is present in URL
        var chapterNumber = parts.chapterNumber ?: "001"
        var chapterLabel = "Capítulo $chapterNumber"
        var pages = emptyList<ExtractedPageCandidate>()

        if (!parts.chapterId.isNullOrBlank()) {
            val chapterJson = fetchJson("$baseOrigin/api/v2/chapters/${parts.chapterId}", sourceUrl)
            if (chapterJson != null) {
                val parsed = parseChapterApiResponse(chapterJson, sourceUrl, chapterNumber)
                pages = parsed.first
                if (parsed.second.isNotBlank()) {
                    chapterNumber = parsed.second
                    chapterLabel = "Capítulo $chapterNumber"
                }
            }
        }

        // 3. Fetch chapter list so batch auto-download works directly from either series or chapter URL
        val chaptersJson = fetchJson(
            "$baseOrigin/api/v2/manga/${parts.hashId}/chapters?limit=500&order[number]=asc",
            sourceUrl
        ) ?: fetchJson("$baseOrigin/api/v2/manga/${parts.hashId}/chapters?limit=500", sourceUrl)

        val detectedChapters = if (chaptersJson != null) {
            parseChaptersListApiResponse(
                json = chaptersJson,
                baseOrigin = baseOrigin,
                seriesSlug = parts.seriesSlug,
                currentSourceUrl = sourceUrl
            )
        } else {
            emptyList()
        }

        if (pages.isEmpty() && detectedChapters.isEmpty()) {
            return null
        }

        return ExtractionResult(
            sourceUrl = sourceUrl,
            siteHost = parts.host,
            seriesTitle = seriesTitle,
            chapterLabel = chapterLabel,
            chapterNumber = chapterNumber,
            comicFormat = comicFormat,
            pages = pages,
            detectedChapters = detectedChapters,
            usedAiFilter = false
        )
    }

    fun parseChapterApiResponse(
        json: JSONObject,
        refererUrl: String,
        fallbackChapterNumber: String
    ): Pair<List<ExtractedPageCandidate>, String> {
        val rootObj = json.optJSONObject("result")
            ?: json.optJSONObject("data")
            ?: json
        val resultObj = rootObj.optJSONObject("chapter") ?: rootObj

        val num = resultObj.optString("number").ifBlank {
            resultObj.optString("chapter").ifBlank { fallbackChapterNumber }
        }
        val imagesArray = resultObj.optJSONArray("images")
            ?: resultObj.optJSONArray("pages")
            ?: resultObj.optJSONArray("files")
            ?: rootObj.optJSONArray("images")
            ?: rootObj.optJSONArray("pages")
            ?: JSONArray()

        val pages = mutableListOf<ExtractedPageCandidate>()
        for (i in 0 until imagesArray.length()) {
            val entry = imagesArray.opt(i) ?: continue
            when (entry) {
                is JSONObject -> {
                    val imgUrl = entry.optString("url")
                        .ifBlank { entry.optString("src") }
                        .ifBlank { entry.optString("image") }
                        .ifBlank { entry.optString("image_url") }
                        .ifBlank { entry.optString("imageUrl") }
                        .ifBlank { entry.optString("file") }
                        .ifBlank { entry.optString("original") }
                        .ifBlank { entry.optString("path") }
                    if (imgUrl.startsWith("http://") || imgUrl.startsWith("https://")) {
                        pages.add(
                            ExtractedPageCandidate(
                                index = pages.size,
                                imageUrl = imgUrl,
                                refererUrl = refererUrl,
                                widthHint = entry.optInt("width", 0),
                                heightHint = entry.optInt("height", 0)
                            )
                        )
                    }
                }
                is String -> {
                    if (entry.startsWith("http://") || entry.startsWith("https://")) {
                        pages.add(
                            ExtractedPageCandidate(
                                index = pages.size,
                                imageUrl = entry,
                                refererUrl = refererUrl
                            )
                        )
                    }
                }
            }
        }
        return pages to num
    }

    fun parseChaptersListApiResponse(
        json: JSONObject,
        baseOrigin: String,
        seriesSlug: String,
        currentSourceUrl: String
    ): List<DetectedChapterLink> {
        val resultObj = json.optJSONObject("result") ?: json.optJSONObject("data")
        val items = resultObj?.optJSONArray("items")
            ?: resultObj?.optJSONArray("chapters")
            ?: json.optJSONArray("items")
            ?: json.optJSONArray("data")
            ?: return emptyList()

        val links = LinkedHashMap<String, DetectedChapterLink>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val chId = item.optString("chapter_id").ifBlank { item.optString("id") }
            val number = item.optString("number").ifBlank { item.optString("chapter") }
            if (chId.isBlank() || number.isBlank()) continue

            val cleanNumber = number.removeSuffix(".0")
            val chUrl = "$baseOrigin/title/$seriesSlug/$chId-chapter-$cleanNumber"
            if (chUrl == currentSourceUrl) continue

            val chName = item.optString("name").trim()
            val title = if (chName.isNotBlank() && !chName.equals("null", ignoreCase = true)) {
                "Capítulo $cleanNumber — $chName"
            } else {
                "Capítulo $cleanNumber"
            }

            if (!links.containsKey(chUrl)) {
                links[chUrl] = DetectedChapterLink(
                    title = title,
                    chapterNumber = cleanNumber,
                    url = chUrl
                )
            }
        }
        return links.values
            .sortedBy { it.chapterNumber.toDoubleOrNull() ?: Double.MAX_VALUE }
            .take(300)
    }

    private fun fetchJson(apiUrl: String, refererUrl: String): JSONObject? {
        return try {
            val builder = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", refererUrl)
                .header("Origin", WebSessionCookieStore.originOf(refererUrl))
            WebSessionCookieStore.getCookieForUrl(apiUrl)?.let { cookie ->
                builder.header("Cookie", cookie)
            }
            okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank() || !body.trimStart().startsWith("{")) return null
                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }
}
