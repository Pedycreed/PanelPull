package com.example.data.model

enum class ComicFormat(val label: String) {
    MANHWA("Manhwa"),
    MANGA("Mangá"),
    WEBTOON("Webtoon")
}

enum class DownloadStatus(val label: String) {
    EXTRACTED("Extraído"),
    DOWNLOADING("Baixando"),
    COMPLETED("Organizado"),
    PARTIAL("Parcial"),
    FAILED("Falhou")
}

data class ExtractedPageCandidate(
    val index: Int,
    val imageUrl: String,
    val refererUrl: String,
    val altText: String = "",
    val widthHint: Int = 0,
    val heightHint: Int = 0,
    val isAiVerified: Boolean = false
)

data class DetectedChapterLink(
    val title: String,
    val chapterNumber: String,
    val url: String,
    val selected: Boolean = true
)

data class ExtractionResult(
    val sourceUrl: String,
    val siteHost: String,
    val seriesTitle: String,
    val chapterLabel: String,
    val chapterNumber: String,
    val comicFormat: ComicFormat,
    val pages: List<ExtractedPageCandidate>,
    val detectedChapters: List<DetectedChapterLink>,
    val usedAiFilter: Boolean = false
)

enum class StudioAspectRatio(
    val label: String,
    val widthRatio: Float,
    val heightRatio: Float
) {
    RATIO_1_1("1:1", 1f, 1f),
    RATIO_2_3("2:3", 2f, 3f),
    RATIO_3_2("3:2", 3f, 2f),
    RATIO_3_4("3:4", 3f, 4f),
    RATIO_4_3("4:3", 4f, 3f),
    RATIO_9_16("9:16", 9f, 16f),
    RATIO_16_9("16:9", 16f, 9f),
    RATIO_21_9("21:9", 21f, 9f)
}
