package com.example.data.extractor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

private const val IMAGE_LOADER_TAG = "ComicImageLoader"
private const val HEADER_REFERER_SOURCE = "X-PanelPull-Referer"

data class FetchedImageResult(
    val bytes: ByteArray,
    val contentType: String?,
    val fromCache: Boolean
)

/**
 * Shared Coil ImageLoader + throttled OkHttp client tailored for manga/manhwa/webtoon CDNs
 * (such as wowpic / comix.to / Cloudflare-fronted image hosts):
 * - Limits concurrency to 2 requests per host to avoid HTTP 429/403 burst throttling.
 * - Sends browser-authentic cross-origin <img> headers (origin Referer, no Origin header,
 *   WebP/PNG/JPEG Accept preference) and automatically retries with fallback header strategies
 *   and backoff on HTTP 403/429/503.
 * - Exposes Coil's disk cache directly so Copy / Single-Page Download / AI Analysis reuse
 *   already-rendered images instantly without hitting the CDN a second time.
 */
object ComicImageLoader {

    @Volatile
    private var sharedImageLoader: ImageLoader? = null

    @Volatile
    private var sharedOkHttpClient: OkHttpClient? = null

    fun getImageOkHttpClient(): OkHttpClient {
        return sharedOkHttpClient ?: synchronized(this) {
            sharedOkHttpClient ?: buildImageOkHttpClient().also { sharedOkHttpClient = it }
        }
    }

    fun getImageLoader(context: Context): ImageLoader {
        return sharedImageLoader ?: synchronized(this) {
            sharedImageLoader ?: buildImageLoader(context.applicationContext).also {
                sharedImageLoader = it
            }
        }
    }

    private fun buildImageOkHttpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 12
            maxRequestsPerHost = 2
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(ComicCdnRetryInterceptor())
            .build()
    }

    private fun buildImageLoader(appContext: Context): ImageLoader {
        return ImageLoader.Builder(appContext)
            .okHttpClient { getImageOkHttpClient() }
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "comic_image_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L)
                    .build()
            }
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AndroidImageDecoderFallbackFactory())
                }
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    fun buildImageRequest(
        context: Context,
        localFile: File?,
        imageUrl: String,
        refererUrl: String,
        retryKey: Int = 0
    ): ImageRequest {
        val cacheKey = if (localFile != null) {
            "${localFile.absolutePath}_${localFile.lastModified()}_${localFile.length()}"
        } else {
            imageUrl
        }
        val builder = ImageRequest.Builder(context)
            .data(localFile ?: imageUrl)
            .diskCacheKey(cacheKey)
            .memoryCacheKey("${cacheKey}_r$retryKey")
            .crossfade(true)

        if (localFile != null) {
            // Avoid GL_MAX_TEXTURE_SIZE failures on tall stitched webtoon strips
            builder.allowHardware(false)
        } else if (refererUrl.isNotBlank()) {
            builder.addHeader(HEADER_REFERER_SOURCE, refererUrl)
        }
        return builder.build()
    }

    /**
     * Fetches raw image bytes, checking Coil's shared disk cache FIRST so copying or saving a
     * page that is already displayed on screen succeeds instantaneously with zero extra CDN hits.
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun fetchImageBytes(
        context: Context,
        imageUrl: String,
        refererUrl: String
    ): FetchedImageResult? = withContext(Dispatchers.IO) {
        // 1. Reuse Coil disk cache snapshot if the page was already fetched for preview
        val loader = getImageLoader(context)
        try {
            loader.diskCache?.openSnapshot(imageUrl)?.use { snapshot ->
                val cachedFile = snapshot.data.toFile()
                if (cachedFile.exists() && cachedFile.length() > 100L) {
                    return@withContext FetchedImageResult(
                        bytes = cachedFile.readBytes(),
                        contentType = null,
                        fromCache = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(IMAGE_LOADER_TAG, "DiskCache read skipped for $imageUrl", e)
        }

        // 2. Fetch via the throttled, multi-strategy retrying OkHttp client
        val request = Request.Builder()
            .url(imageUrl)
            .apply {
                if (refererUrl.isNotBlank()) {
                    header(HEADER_REFERER_SOURCE, refererUrl)
                }
            }
            .build()

        return@withContext try {
            getImageOkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(IMAGE_LOADER_TAG, "HTTP ${response.code} fetching image: $imageUrl")
                    return@use null
                }
                val bytes = response.body?.bytes() ?: return@use null
                if (bytes.size < 100) return@use null
                FetchedImageResult(
                    bytes = bytes,
                    contentType = response.header("Content-Type"),
                    fromCache = false
                )
            }
        } catch (e: Exception) {
            Log.e(IMAGE_LOADER_TAG, "Network error fetching image: $imageUrl", e)
            null
        }
    }

    /**
     * Detects actual image format from file magic bytes so Clipboard / Gallery / FileProvider
     * always receive the right file extension and MIME type.
     */
    fun detectFormatFromBytes(bytes: ByteArray, fallbackUrl: String = ""): Pair<String, String> {
        if (bytes.size >= 12) {
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) {
                return "png" to "image/png"
            }
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "jpg" to "image/jpeg"
            }
            // WEBP: RIFF....WEBP
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                return "webp" to "image/webp"
            }
            // AVIF / HEIF: ....ftypavif or ....ftypavis
            if (bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            ) {
                val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
                if (brand.startsWith("avi")) {
                    return "avif" to "image/avif"
                }
            }
            // GIF: GIF8
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
            ) {
                return "gif" to "image/gif"
            }
        }
        val lower = fallbackUrl.lowercase()
        return when {
            lower.contains(".png") -> "png" to "image/png"
            lower.contains(".webp") -> "webp" to "image/webp"
            lower.contains(".avif") -> "avif" to "image/avif"
            else -> "jpg" to "image/jpeg"
        }
    }

    /**
     * Checks whether raw bytes look like an HTML error page (e.g. Cloudflare challenge returned with 200)
     * rather than real image binary data.
     */
    fun looksLikeHtmlNotImage(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return true
        val prefix = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart().lowercase()
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("<head")
    }
}

/**
 * OkHttp interceptor that applies real browser image headers and retries automatically
 * across fallback Referer/Cookie strategies and rate-limit backoffs (HTTP 403/429/503).
 */
private class ComicCdnRetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val urlStr = original.url.toString()
        val rawReferer = original.header(HEADER_REFERER_SOURCE)
            ?: original.header("Referer")
            ?: ""
        val originReferer = if (rawReferer.isNotBlank()) {
            WebSessionCookieStore.originOf(rawReferer).trimEnd('/') + "/"
        } else {
            ""
        }
        val cookieForImageHost = WebSessionCookieStore.getCookieForUrl(urlStr)

        // Strategy 1: Standard Chrome cross-origin <img> request (origin Referer, NO Origin header)
        val req1 = buildCleanImageRequest(
            base = original,
            referer = originReferer.ifBlank { null },
            cookie = cookieForImageHost,
            accept = "image/webp,image/png,image/jpeg,image/apng,image/*,*/*;q=0.8",
            includeSecFetch = true
        )
        var response = chain.proceed(req1)
        if (response.isSuccessful) return response

        val firstCode = response.code
        // Rate-limited (429 / 503): back off gently and retry
        if (firstCode == 429 || firstCode == 503) {
            response.close()
            try {
                Thread.sleep(750L)
            } catch (_: InterruptedException) {
            }
            response = chain.proceed(req1)
            if (response.isSuccessful) return response
        }

        // Auth/Hotlink rejection (403 / 401 / 400): retry with full chapter Referer
        if (response.code in setOf(400, 401, 403, 406, 429, 503) &&
            rawReferer.isNotBlank() && rawReferer != originReferer
        ) {
            response.close()
            val req2 = buildCleanImageRequest(
                base = original,
                referer = rawReferer,
                cookie = cookieForImageHost,
                accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                includeSecFetch = true
            )
            response = chain.proceed(req2)
            if (response.isSuccessful) return response
        }

        // Final fallback: clean request without Referer or Cookie (some CDNs reject third-party cookies/referers)
        if (response.code in setOf(400, 401, 403, 406)) {
            response.close()
            val req3 = buildCleanImageRequest(
                base = original,
                referer = null,
                cookie = null,
                accept = "image/webp,image/png,image/jpeg,image/*,*/*;q=0.8",
                includeSecFetch = false
            )
            response = chain.proceed(req3)
        }

        return response
    }

    private fun buildCleanImageRequest(
        base: Request,
        referer: String?,
        cookie: String?,
        accept: String,
        includeSecFetch: Boolean
    ): Request {
        val builder = base.newBuilder()
            .removeHeader(HEADER_REFERER_SOURCE)
            .removeHeader("Origin")
            .removeHeader("Referer")
            .removeHeader("Cookie")
            .header("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }
        if (includeSecFetch) {
            builder.header("Sec-Fetch-Dest", "image")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        }
        return builder.build()
    }
}

/**
 * Uses Android's native ImageDecoder (API 28+) for AVIF / HEIF images that BitmapFactory cannot decode.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private class AndroidImageDecoderFallbackFactory : Decoder.Factory {
    override fun create(
        result: SourceResult,
        options: Options,
        imageLoader: ImageLoader
    ): Decoder? {
        val mime = result.mimeType.orEmpty().lowercase()
        val sourceFileName = result.source.fileOrNull()?.name.orEmpty().lowercase()
        val hasAvifHint = mime.contains("avif") || mime.contains("heif") || mime.contains("heic") ||
            sourceFileName.endsWith(".avif") || sourceFileName.endsWith(".heif")
        val hasAvifMagic = runCatching {
            val peeked = result.source.source().peek()
            if (peeked.request(12L)) {
                val header = peeked.readByteArray(12L)
                ComicImageLoader.detectFormatFromBytes(header).first == "avif"
            } else {
                false
            }
        }.getOrDefault(false)

        if (!hasAvifHint && !hasAvifMagic) return null
        return AndroidImageDecoderDecoder(result.source, options)
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private class AndroidImageDecoderDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {
    override suspend fun decode(): DecodeResult = withContext(Dispatchers.IO) {
        val file = source.file().toFile()
        val imageSource = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxDim = 2048
            val w = info.size.width
            val h = info.size.height
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false
        )
    }
}
