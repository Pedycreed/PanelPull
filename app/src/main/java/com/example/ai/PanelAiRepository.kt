package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.ComicFormat
import com.example.data.model.ExtractedPageCandidate
import com.example.data.model.StudioAspectRatio
import com.example.data.prefs.CustomAiSettings
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingConfig
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "PanelAiRepository"

data class AiExtractionFilterResult(
    val seriesTitle: String,
    val chapterNumber: String,
    val comicFormat: ComicFormat,
    val keptIndices: List<Int>
)

class PanelAiRepository(
    private val customApiClient: CustomAiApiClient = CustomAiApiClient()
) {

    private val fastFilterModel by lazy {
        val filterSchema = Schema.obj(
            properties = mapOf(
                "seriesTitle" to Schema.string("Clean title of the manga, manhwa, or webtoon"),
                "chapterNumber" to Schema.string("Numeric chapter identifier such as 42 or 12.5"),
                "comicFormat" to Schema.enumeration(
                    values = listOf("MANHWA", "MANGA", "WEBTOON"),
                    description = "Detected comic format"
                ),
                "excludedIndices" to Schema.array(
                    items = Schema.integer("Zero-based index of NON-story noise images to REMOVE (ads, logos, scanlator credit banners, avatars, unrelated covers)"),
                    description = "Only indices of obvious ads, logos, avatars, banners, or unrelated covers. Leave empty if all candidates are real comic pages."
                )
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.1-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = filterSchema
            }
        )
    }

    private val visionModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-4-neon")
    }

    private val highThinkingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-carbon",
            generationConfig = generationConfig {
                thinkingConfig = ThinkingConfig.Builder()
                    .setThinkingLevel(ThinkingLevel.HIGH)
                    .build()
                // maxOutputTokens intentionally omitted when ThinkingLevel.HIGH is active
            }
        )
    }

    private val generalModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-4-hydrogen")
    }

    suspend fun testCustomApiConnection(settings: CustomAiSettings): String {
        return withTimeout(30_000L) {
            customApiClient.testConnection(settings)
        }
    }

    suspend fun filterExtractedPagesFast(
        sourceUrl: String,
        rawTitle: String,
        candidates: List<ExtractedPageCandidate>,
        aiSettings: CustomAiSettings = CustomAiSettings()
    ): AiExtractionFilterResult {
        if (candidates.isEmpty()) {
            return AiExtractionFilterResult(
                seriesTitle = rawTitle,
                chapterNumber = "001",
                comicFormat = ComicFormat.MANHWA,
                keptIndices = emptyList()
            )
        }

        // For large chapters, inspect the first 20, last 20, and any URLs from outlier hosts
        // so uniform middle story pages are NEVER truncated regardless of chapter length (100–500+ pages).
        val dominantHost = candidates
            .mapNotNull { runCatching { java.net.URI(it.imageUrl).host?.lowercase() }.getOrNull() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val sampleToInspect = if (candidates.size <= 80) {
            candidates
        } else {
            val selected = LinkedHashSet<ExtractedPageCandidate>()
            selected.addAll(candidates.take(20))
            candidates.forEach { item ->
                val h = runCatching { java.net.URI(item.imageUrl).host?.lowercase() }.getOrNull()
                if (dominantHost != null && h != dominantHost) {
                    selected.add(item)
                }
            }
            selected.addAll(candidates.takeLast(20))
            selected.toList().sortedBy { it.index }.take(80)
        }

        val listing = buildString {
            appendLine("URL: $sourceUrl")
            appendLine("Page title: $rawTitle")
            appendLine("Total pages extracted: ${candidates.size}")
            appendLine("Candidate images to audit:")
            sampleToInspect.forEach { item ->
                appendLine("[${item.index}] ${item.imageUrl} (alt='${item.altText}', size=${item.widthHint}x${item.heightHint})")
            }
        }
        val prompt = """
            Você é um filtro de extração para mangás, manhwas e webtoons.
            Responda APENAS com um objeto JSON válido contendo as chaves:
            {"seriesTitle":"...","chapterNumber":"...","comicFormat":"MANHWA|MANGA|WEBTOON","excludedIndices":[...]}
            1) Identifique o nome limpo da obra (seriesTitle), o número do capítulo (chapterNumber) e o formato (MANHWA, MANGA ou WEBTOON).
            2) Em excludedIndices, liste SOMENTE os índices que forem CLARAMENTE anúncios, banners de scanlator/Discord/Pix/doações, avatares, ícones de UI ou capas de outras obras.
            3) NUNCA exclua páginas reais do capítulo. Se todas parecerem páginas válidas do capítulo, retorne excludedIndices vazio [].
            $listing
        """.trimIndent()

        val rawJsonText = withTimeout(45_000L) {
            if (aiSettings.isCustomApiActive) {
                customApiClient.generateText(
                    settings = aiSettings,
                    prompt = prompt,
                    bitmap = null,
                    expectJson = true
                )
            } else {
                fastFilterModel.generateContent(prompt).text
                    ?: throw IllegalStateException("Modelo retornou resposta vazia ao filtrar URL.")
            }
        }
        val cleanedJson = stripMarkdownJsonFences(rawJsonText)
        val json = JSONObject(cleanedJson)
        val series = json.optString("seriesTitle", rawTitle).ifBlank { rawTitle }
        val chapter = json.optString("chapterNumber", "001").ifBlank { "001" }
        val format = when (json.optString("comicFormat", "MANHWA").uppercase()) {
            "MANGA" -> ComicFormat.MANGA
            "WEBTOON" -> ComicFormat.WEBTOON
            else -> ComicFormat.MANHWA
        }

        val validIndexSet = candidates.map { it.index }.toSet()
        val excludedArray = json.optJSONArray("excludedIndices")
        val excluded = if (excludedArray != null) {
            (0 until excludedArray.length())
                .mapNotNull { i -> excludedArray.optInt(i, -1).takeIf { it in validIndexSet } }
                .toSet()
        } else {
            emptySet()
        }

        // Guardrail: never allow AI noise filter to wipe out >30% of a multi-page chapter
        val safeExcluded = if (candidates.size >= 10 && excluded.size > (candidates.size * 0.30).toInt()) {
            emptySet()
        } else {
            excluded
        }

        val kept = candidates.map { it.index }.filterNot { it in safeExcluded }
        return AiExtractionFilterResult(
            seriesTitle = series,
            chapterNumber = chapter,
            comicFormat = format,
            keptIndices = if (kept.isNotEmpty()) kept else candidates.map { it.index }
        )
    }

    fun analyzeComicPageStream(
        bitmap: Bitmap,
        userPrompt: String,
        aiSettings: CustomAiSettings = CustomAiSettings()
    ): Flow<String> = flow {
        val fullPrompt = if (userPrompt.isBlank()) {
            "Analise esta página de mangá/manhwa/webtoon em português do Brasil: " +
                "1) Traduza todos os balões de fala, caixas de narração e onomatopeias (SFX) em ordem de leitura; " +
                "2) Descreva brevemente a ação visual, expressões e composição do quadro."
        } else {
            userPrompt
        }

        if (aiSettings.isCustomApiActive) {
            val text = withTimeout(60_000L) {
                customApiClient.generateText(
                    settings = aiSettings,
                    prompt = fullPrompt,
                    bitmap = bitmap,
                    expectJson = false
                )
            }
            emit(text)
            return@flow
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            visionModel.generateContentStream(
                content {
                    image(bitmap)
                    text(fullPrompt)
                }
            ).collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    accumulated.append(piece)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("O modelo não retornou análise para esta imagem.")
        }
    }

    fun analyzeDeepThinkingStream(
        bitmap: Bitmap?,
        prompt: String,
        aiSettings: CustomAiSettings = CustomAiSettings()
    ): Flow<String> = flow {
        if (aiSettings.isCustomApiActive) {
            val text = withTimeout(60_000L) {
                customApiClient.generateText(
                    settings = aiSettings,
                    prompt = prompt,
                    bitmap = bitmap,
                    expectJson = false
                )
            }
            emit(text)
            return@flow
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            val flowSource = if (bitmap != null) {
                highThinkingModel.generateContentStream(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            } else {
                highThinkingModel.generateContentStream(prompt)
            }
            flowSource.collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    accumulated.append(piece)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("O modo de pensamento profundo retornou resposta vazia.")
        }
    }

    fun askGeneralAssistantStream(
        bitmap: Bitmap?,
        prompt: String,
        aiSettings: CustomAiSettings = CustomAiSettings()
    ): Flow<String> = flow {
        if (aiSettings.isCustomApiActive) {
            val text = withTimeout(60_000L) {
                customApiClient.generateText(
                    settings = aiSettings,
                    prompt = prompt,
                    bitmap = bitmap,
                    expectJson = false
                )
            }
            emit(text)
            return@flow
        }

        val accumulated = StringBuilder()
        withTimeout(60_000L) {
            val flowSource = if (bitmap != null) {
                generalModel.generateContentStream(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            } else {
                generalModel.generateContentStream(prompt)
            }
            flowSource.collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    accumulated.append(piece)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("O assistente retornou resposta vazia.")
        }
    }

    suspend fun generateStudioArtwork(
        prompt: String,
        aspectRatio: StudioAspectRatio,
        useProQuality: Boolean,
        referenceBitmap: Bitmap? = null,
        aiSettings: CustomAiSettings = CustomAiSettings()
    ): Bitmap {
        if (aiSettings.isCustomApiActive) {
            return withTimeout(60_000L) {
                customApiClient.generateImage(
                    settings = aiSettings,
                    prompt = prompt,
                    aspectRatio = aspectRatio,
                    referenceBitmap = referenceBitmap
                )
            }
        }

        val modelName = if (useProQuality) {
            "gemini-3-pro-image-preview"
        } else {
            "gemini-nano-banana-2.1"
        }

        val config = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
            imageConfig = ImageConfig.builder()
                .setAspectRatio(aspectRatio.toFirebaseAspectRatio())
                .setImageSize(ImageSize.SIZE_1K)
                .build()
        }

        val imageModel = Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = config
        )

        val response = withTimeout(60_000L) {
            if (referenceBitmap != null) {
                imageModel.generateContent(
                    content {
                        image(referenceBitmap)
                        text(prompt)
                    }
                )
            } else {
                imageModel.generateContent(prompt)
            }
        }

        val imagePart = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()

        return imagePart?.image
            ?: throw IllegalStateException("Nenhuma imagem foi gerada pelo modelo ($modelName).")
    }

    private fun stripMarkdownJsonFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        val withoutStart = if (firstNewline >= 0) trimmed.substring(firstNewline + 1) else trimmed.removePrefix("```")
        return withoutStart.removeSuffix("```").trim()
    }

    private fun StudioAspectRatio.toFirebaseAspectRatio(): AspectRatio = when (this) {
        StudioAspectRatio.RATIO_1_1 -> AspectRatio.SQUARE_1x1
        StudioAspectRatio.RATIO_2_3 -> AspectRatio.PORTRAIT_2x3
        StudioAspectRatio.RATIO_3_2 -> AspectRatio.LANDSCAPE_3x2
        StudioAspectRatio.RATIO_3_4 -> AspectRatio.PORTRAIT_3x4
        StudioAspectRatio.RATIO_4_3 -> AspectRatio.LANDSCAPE_4x3
        StudioAspectRatio.RATIO_9_16 -> AspectRatio.PORTRAIT_9x16
        StudioAspectRatio.RATIO_16_9 -> AspectRatio.LANDSCAPE_16x9
        StudioAspectRatio.RATIO_21_9 -> AspectRatio.LANDSCAPE_21x9
    }

    companion object {
        fun logAiFailure(context: String, error: Throwable) {
            if (error is CancellationException) throw error
            Log.e(TAG, "AI failure in $context", error)
        }
    }
}
