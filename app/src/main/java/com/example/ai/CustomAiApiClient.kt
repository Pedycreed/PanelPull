package com.example.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.data.model.StudioAspectRatio
import com.example.data.prefs.AiApiProvider
import com.example.data.prefs.ApiKeySecureStore
import com.example.data.prefs.CustomAiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Direct HTTPS client for user-configured API keys (Google Gemini API or OpenAI-compatible APIs).
 * Uses an isolated OkHttpClient without header/body logging and sends keys exclusively via
 * HTTP headers (`x-goog-api-key` or `Authorization: Bearer`) so keys are never leaked in URLs or logs.
 */
class CustomAiApiClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateText(
        settings: CustomAiSettings,
        prompt: String,
        bitmap: Bitmap? = null,
        expectJson: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey.trim()
        require(key.isNotEmpty()) { "Informe uma API Key válida." }

        when (settings.provider) {
            AiApiProvider.GEMINI_API -> callGeminiGenerateText(
                apiKey = key,
                model = settings.effectiveModel,
                prompt = prompt,
                bitmap = bitmap,
                expectJson = expectJson
            )
            AiApiProvider.OPENAI_COMPATIBLE -> callOpenAiChat(
                apiKey = key,
                baseUrl = settings.effectiveBaseUrl,
                model = settings.effectiveModel,
                prompt = prompt,
                bitmap = bitmap,
                expectJson = expectJson
            )
            AiApiProvider.FIREBASE_DEFAULT -> error("Provedor customizado não selecionado.")
        }
    }

    suspend fun generateImage(
        settings: CustomAiSettings,
        prompt: String,
        aspectRatio: StudioAspectRatio,
        referenceBitmap: Bitmap? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        val key = settings.apiKey.trim()
        require(key.isNotEmpty()) { "Informe uma API Key válida." }

        when (settings.provider) {
            AiApiProvider.GEMINI_API -> callGeminiGenerateImage(
                apiKey = key,
                userModel = settings.effectiveModel,
                prompt = prompt,
                aspectRatio = aspectRatio,
                referenceBitmap = referenceBitmap
            )
            AiApiProvider.OPENAI_COMPATIBLE -> callOpenAiGenerateImage(
                apiKey = key,
                baseUrl = settings.effectiveBaseUrl,
                prompt = prompt,
                aspectRatio = aspectRatio
            )
            AiApiProvider.FIREBASE_DEFAULT -> error("Provedor customizado não selecionado.")
        }
    }

    suspend fun testConnection(settings: CustomAiSettings): String = withContext(Dispatchers.IO) {
        val reply = generateText(
            settings = settings,
            prompt = "Responda apenas com: OK",
            bitmap = null,
            expectJson = false
        )
        if (reply.isBlank()) {
            throw IllegalStateException("O provedor respondeu sem conteúdo.")
        }
        "Conexão validada com sucesso (${settings.effectiveModel})!"
    }

    private fun callGeminiGenerateText(
        apiKey: String,
        model: String,
        prompt: String,
        bitmap: Bitmap?,
        expectJson: Boolean
    ): String {
        val cleanModel = model.removePrefix("models/").trim().ifBlank { "gemini-2.5-flash" }
        val encodedModel = URLEncoder.encode(cleanModel, "UTF-8")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent"

        val parts = JSONArray()
        if (bitmap != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeBitmapToJpegBase64(bitmap))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))

        val bodyJson = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", parts)
            )
        )
        if (expectJson) {
            bodyJson.put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json")
            )
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractSafeApiError(response.code, raw, "Gemini API"))
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates")
            val first = candidates?.optJSONObject(0)
            val respParts = first?.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()
            if (respParts != null) {
                for (i in 0 until respParts.length()) {
                    val partText = respParts.optJSONObject(i)?.optString("text", "").orEmpty()
                    if (partText.isNotEmpty()) {
                        textBuilder.append(partText)
                    }
                }
            }
            val result = textBuilder.toString().trim()
            if (result.isEmpty()) {
                throw IllegalStateException("A Gemini API retornou resposta vazia.")
            }
            return result
        }
    }

    private fun callGeminiGenerateImage(
        apiKey: String,
        userModel: String,
        prompt: String,
        aspectRatio: StudioAspectRatio,
        referenceBitmap: Bitmap?
    ): Bitmap {
        val imageModel = if (userModel.contains("image", ignoreCase = true)) {
            userModel.removePrefix("models/").trim()
        } else {
            "gemini-2.5-flash-image"
        }
        val encodedModel = URLEncoder.encode(imageModel, "UTF-8")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent"

        val parts = JSONArray()
        if (referenceBitmap != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", encodeBitmapToJpegBase64(referenceBitmap))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))

        val bodyJson = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("IMAGE").put("TEXT"))
                    .put("imageConfig", JSONObject().put("aspectRatio", aspectRatio.label))
            )

        val request = Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractSafeApiError(response.code, raw, "Gemini Imagem"))
            }
            val root = JSONObject(raw)
            val respParts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")

            if (respParts != null) {
                for (i in 0 until respParts.length()) {
                    val part = respParts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    val b64 = inline?.optString("data", "").orEmpty()
                    if (b64.isNotBlank()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) return bmp
                    }
                }
            }
            throw IllegalStateException("O modelo ($imageModel) não retornou nenhuma imagem.")
        }
    }

    private fun callOpenAiChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        bitmap: Bitmap?,
        expectJson: Boolean
    ): String {
        val safeBase = ApiKeySecureStore.validateHttpsBaseUrl(baseUrl)
            ?: throw IllegalArgumentException("Base URL inválida. Use um endereço HTTPS público válido.")
        val endpoint = "$safeBase/chat/completions"

        val messageContent: Any = if (bitmap != null) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,${encodeBitmapToJpegBase64(bitmap)}")
                        )
                )
        } else {
            prompt
        }

        val bodyJson = JSONObject()
            .put("model", model.ifBlank { "gpt-4o-mini" })
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", messageContent)
                )
            )
        if (expectJson) {
            bodyJson.put("response_format", JSONObject().put("type", "json_object"))
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractSafeApiError(response.code, raw, "API Compatível"))
            }
            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
            if (content.isEmpty()) {
                throw IllegalStateException("O endpoint retornou resposta vazia.")
            }
            return content
        }
    }

    private fun callOpenAiGenerateImage(
        apiKey: String,
        baseUrl: String,
        prompt: String,
        aspectRatio: StudioAspectRatio
    ): Bitmap {
        val safeBase = ApiKeySecureStore.validateHttpsBaseUrl(baseUrl)
            ?: throw IllegalArgumentException("Base URL inválida. Use um endereço HTTPS público válido.")
        val endpoint = "$safeBase/images/generations"
        val sizeStr = when {
            aspectRatio.widthRatio > aspectRatio.heightRatio -> "1536x1024"
            aspectRatio.heightRatio > aspectRatio.widthRatio -> "1024x1536"
            else -> "1024x1024"
        }

        val bodyJson = JSONObject()
            .put("model", "gpt-image-1")
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", sizeStr)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    extractSafeApiError(
                        response.code,
                        raw,
                        "Geração de imagem"
                    ) + " (Dica: volte ao padrão Firebase AI ou use uma chave Gemini para gerar capas.)"
                )
            }
            val dataItem = JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
            val b64 = dataItem?.optString("b64_json", "").orEmpty()
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Não foi possível decodificar a imagem gerada.")
            }
            val url = dataItem?.optString("url", "").orEmpty()
            val safeImageUrl = ApiKeySecureStore.validateHttpsBaseUrl(url)
            if (safeImageUrl != null) {
                val imgReq = Request.Builder().url(safeImageUrl).get().build()
                httpClient.newCall(imgReq).execute().use { imgResp ->
                    val bytes = imgResp.body?.bytes()
                    if (imgResp.isSuccessful && bytes != null) {
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: throw IllegalStateException("Não foi possível decodificar a imagem retornada.")
                    }
                }
            }
            throw IllegalStateException("O provedor não retornou uma imagem válida.")
        }
    }

    private fun encodeBitmapToJpegBase64(bitmap: Bitmap): String {
        val maxDim = 1600
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val factor = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractSafeApiError(httpCode: Int, rawBody: String, providerLabel: String): String {
        val parsedMsg = runCatching {
            val root = JSONObject(rawBody)
            root.optJSONObject("error")?.optString("message", "").orEmpty()
                .ifBlank { root.optString("message", "") }
        }.getOrDefault("").take(180)

        return when (httpCode) {
            401, 403 -> "$providerLabel: Chave API inválida ou sem permissão (HTTP $httpCode)."
            404 -> "$providerLabel: Modelo ou endpoint não encontrado (HTTP 404). Verifique o nome do modelo."
            429 -> "$providerLabel: Limite de cota/requisições excedido (HTTP 429)."
            else -> if (parsedMsg.isNotBlank()) {
                "$providerLabel (HTTP $httpCode): $parsedMsg"
            } else {
                "$providerLabel retornou erro HTTP $httpCode."
            }
        }
    }
}
