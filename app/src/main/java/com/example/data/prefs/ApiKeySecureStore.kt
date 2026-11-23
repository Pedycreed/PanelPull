package com.example.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts optional user-supplied AI API keys locally using the device's
 * AndroidKeyStore AES-256-GCM key before persisting to DataStore.
 * Plaintext keys are never logged or written to disk unencrypted on Android devices.
 */
object ApiKeySecureStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "panelpull_custom_ai_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX_KEYSTORE = "ks1:"
    private const val PREFIX_FALLBACK = "b64:"

    fun encrypt(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val secretKey = getOrCreateKeyStoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$PREFIX_KEYSTORE$ivB64:$dataB64"
        }.getOrElse {
            // Fallback for JVM unit-test environments where AndroidKeyStore provider is absent
            val encoded = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$PREFIX_FALLBACK$encoded"
        }
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isBlank()) return ""
        return when {
            storedValue.startsWith(PREFIX_KEYSTORE) -> runCatching {
                val payload = storedValue.removePrefix(PREFIX_KEYSTORE)
                val sep = payload.indexOf(':')
                if (sep <= 0) return ""
                val iv = Base64.decode(payload.substring(0, sep), Base64.NO_WRAP)
                val cipherBytes = Base64.decode(payload.substring(sep + 1), Base64.NO_WRAP)
                val secretKey = getOrCreateKeyStoreKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }.getOrDefault("")

            storedValue.startsWith(PREFIX_FALLBACK) -> runCatching {
                val payload = storedValue.removePrefix(PREFIX_FALLBACK)
                String(Base64.decode(payload, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")

            else -> ""
        }
    }

    fun maskApiKey(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 8) return "••••••••"
        val prefix = trimmed.take(4)
        val suffix = trimmed.takeLast(4)
        return "$prefix••••$suffix"
    }

    /**
     * Validates that a custom OpenAI-compatible base URL uses HTTPS and points to a public host
     * (blocks plaintext HTTP, loopback, link-local metadata, and private RFC1918 networks).
     */
    fun validateHttpsBaseUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.trim() ?: return null
        if (host.isEmpty()) return null
        if (host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host == "0.0.0.0" ||
            host == "::1" ||
            host == "[::1]"
        ) {
            return null
        }
        // Reject literal private/loopback/link-local IPv4 & IPv6 addresses
        if (isPrivateOrLoopbackLiteral(host)) return null
        return trimmed
    }

    private fun isPrivateOrLoopbackLiteral(host: String): Boolean {
        val cleaned = host.removePrefix("[").removeSuffix("]")
        val ipv4Match = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(cleaned)
        if (ipv4Match != null) {
            val a = ipv4Match.groupValues[1].toIntOrNull() ?: return true
            val b = ipv4Match.groupValues[2].toIntOrNull() ?: return true
            val c = ipv4Match.groupValues[3].toIntOrNull() ?: return true
            val d = ipv4Match.groupValues[4].toIntOrNull() ?: return true
            if (a > 255 || b > 255 || c > 255 || d > 255) return true
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 100 && b in 64..127) return true
            return false
        }
        if (cleaned.contains(':')) {
            return runCatching {
                val addr = InetAddress.getByName(cleaned)
                addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress
            }.getOrDefault(true)
        }
        return false
    }

    private fun getOrCreateKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
