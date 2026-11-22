package com.example.data.extractor

import android.webkit.CookieManager
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges cookies captured in the in-app WebView (e.g. Cloudflare clearance or CDN session cookies)
 * to OkHttp and Coil requests so protected manga/manhwa/webtoon sites download cleanly.
 */
object WebSessionCookieStore {
    private val hostCookies = ConcurrentHashMap<String, String>()

    fun syncFromWebView(url: String) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrBlank()) {
            extractHost(url)?.let { host ->
                hostCookies[host] = cookie
            }
        }
    }

    fun getCookieForUrl(url: String): String? {
        val liveCookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!liveCookie.isNullOrBlank()) {
            return liveCookie
        }
        val host = extractHost(url) ?: return null
        hostCookies[host]?.let { return it }
        // Match parent domain (e.g., img.comix.to -> comix.to)
        val parts = host.split(".")
        if (parts.size >= 2) {
            val rootDomain = parts.takeLast(2).joinToString(".")
            return hostCookies[rootDomain]
        }
        return null
    }

    fun originOf(url: String): String {
        return try {
            val uri = URI(url)
            val portSuffix = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portSuffix"
        } catch (_: Exception) {
            url
        }
    }

    private fun extractHost(url: String): String? {
        return try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
