package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.PanelAiRepository
import com.example.data.extractor.ChapterDownloadManager
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.extractor.DownloadProgress
import com.example.data.local.AppDatabase
import com.example.data.local.ChapterEntity
import com.example.data.local.ChapterWithPages
import com.example.data.local.LibraryRepository
import com.example.data.local.PageEntity
import com.example.data.local.SeriesEntity
import com.example.data.local.SeriesWithChapters
import com.example.data.model.DetectedChapterLink
import com.example.data.model.DownloadStatus
import com.example.data.model.ExtractionResult
import com.example.data.model.StudioAspectRatio
import com.example.data.prefs.UserPreferences
import com.example.data.prefs.UserPreferencesRepository
import com.example.data.util.ApkExportHelper
import com.example.data.util.InstalledApkInfo
import com.example.data.util.PageActionsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "PanelPullViewModel"

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Loading(val stageMessage: String) : ExtractionUiState
    data class Success(
        val chapterWithPages: ChapterWithPages,
        val seriesTitle: String,
        val comicFormatLabel: String,
        val detectedChapters: List<DetectedChapterLink>,
        val usedAiFilter: Boolean
    ) : ExtractionUiState
    data class Error(val message: String) : ExtractionUiState
}

enum class AiAnalysisMode(val title: String, val modelBadge: String, val subtitle: String) {
    VISION_FAST(
        title = "Visão & Tradução",
        modelBadge = "gemini-4-neon",
        subtitle = "Traduz balões, SFX e analisa quadros"
    ),
    HIGH_THINKING(
        title = "Pensamento Profundo",
        modelBadge = "gemini-4-carbon • HIGH",
        subtitle = "Lore, simbolismo e nuances complexas"
    ),
    GENERAL_ASSISTANT(
        title = "Assistente Geral",
        modelBadge = "gemini-4-hydrogen",
        subtitle = "Sinopse, tags e dúvidas gerais"
    )
}

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Streaming(val text: String) : AiUiState
    data class Success(val text: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

sealed interface StudioUiState {
    data object Idle : StudioUiState
    data object Loading : StudioUiState
    data class Success(
        val bitmap: Bitmap,
        val aspectRatio: StudioAspectRatio,
        val prompt: String
    ) : StudioUiState
    data class Error(val message: String) : StudioUiState
}

data class BatchDownloadStatus(
    val currentChapterIndex: Int,
    val totalChapters: Int,
    val currentChapterTitle: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val extractorEngine = ComicExtractorEngine()
    private val database = AppDatabase.getInstance(application)
    private val libraryRepository = LibraryRepository(
        dao = database.comicDao(),
        baseChaptersDir = File(application.filesDir, "chapters").apply { mkdirs() }
    )
    private val downloadManager = ChapterDownloadManager(
        context = application,
        repository = libraryRepository,
        okHttpClient = extractorEngine.okHttpClient
    )
    private val prefsRepository = UserPreferencesRepository(application)
    private val pageActionsHelper = PageActionsHelper(application, extractorEngine.okHttpClient)
    private val apkExportHelper = ApkExportHelper(application)
    private val aiRepository = PanelAiRepository()

    val installedApkInfo: InstalledApkInfo
        get() = apkExportHelper.getInstalledApkInfo()

    private val _isExportingApk = MutableStateFlow(false)
    val isExportingApk: StateFlow<Boolean> = _isExportingApk.asStateFlow()

    val preferences: StateFlow<UserPreferences> = prefsRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val library: StateFlow<List<SeriesWithChapters>> = libraryRepository.libraryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _batchProgress = MutableStateFlow<BatchDownloadStatus?>(null)
    val batchProgress: StateFlow<BatchDownloadStatus?> = _batchProgress.asStateFlow()

    private val _aiBitmap = MutableStateFlow<Bitmap?>(null)
    val aiBitmap: StateFlow<Bitmap?> = _aiBitmap.asStateFlow()

    private val _aiBitmapLabel = MutableStateFlow("Nenhuma página selecionada")
    val aiBitmapLabel: StateFlow<String> = _aiBitmapLabel.asStateFlow()

    private val _aiMode = MutableStateFlow(AiAnalysisMode.VISION_FAST)
    val aiMode: StateFlow<AiAnalysisMode> = _aiMode.asStateFlow()

    private val _aiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiState: StateFlow<AiUiState> = _aiState.asStateFlow()

    private val _studioAspectRatio = MutableStateFlow(StudioAspectRatio.RATIO_2_3)
    val studioAspectRatio: StateFlow<StudioAspectRatio> = _studioAspectRatio.asStateFlow()

    private val _studioState = MutableStateFlow<StudioUiState>(StudioUiState.Idle)
    val studioState: StateFlow<StudioUiState> = _studioState.asStateFlow()

    private val _studioReferenceBitmap = MutableStateFlow<Bitmap?>(null)
    val studioReferenceBitmap: StateFlow<Bitmap?> = _studioReferenceBitmap.asStateFlow()

    private val _readerChapterId = MutableStateFlow<Long?>(null)
    val readerChapterId: StateFlow<Long?> = _readerChapterId.asStateFlow()

    private val _readerInitialPage = MutableStateFlow(0)
    val readerInitialPage: StateFlow<Int> = _readerInitialPage.asStateFlow()

    private val _headlessExtractUrl = MutableStateFlow<String?>(null)
    val headlessExtractUrl: StateFlow<String?> = _headlessExtractUrl.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private var activeExtractJob: Job? = null
    private var activeDownloadJob: Job? = null
    private var activeAiJob: Job? = null
    private var pendingFastPassFallback: ExtractionResult? = null

    fun observeChapter(chapterId: Long) = libraryRepository.observeChapter(chapterId)

    fun updateUrlInput(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setAutoDownload(enabled) }
    }

    fun setAiSmartFilter(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setAiSmartFilter(enabled) }
    }

    fun setSaveToGallery(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setSaveToGallery(enabled) }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setDarkTheme(enabled) }
    }

    fun extractFromUrl(rawUrl: String = _urlInput.value) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            _extractionState.value = ExtractionUiState.Error("Cole uma URL de capítulo ou página da obra.")
            return
        }
        val normalizedUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        _urlInput.value = normalizedUrl
        _headlessExtractUrl.value = null
        pendingFastPassFallback = null
        activeExtractJob?.cancel()
        activeExtractJob = viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Lendo API/HTML e detectando páginas…")
            try {
                val rawResult = extractorEngine.extractFromUrl(normalizedUrl)
                val looksLikeChapterUrl = Regex(
                    """(?i)(chapter|chap|cap[ií]tulo|cap|ep|ch)[-_/.#\s]*\d+"""
                ).containsMatchIn(normalizedUrl)

                if (rawResult.pages.isNotEmpty() || (!looksLikeChapterUrl && rawResult.detectedChapters.isNotEmpty())) {
                    finalizeExtraction(rawResult)
                } else {
                    // Site returned empty pages (SPA / Next.js hydration / obfuscated API) — automatically run headless Chromium JS engine
                    pendingFastPassFallback = rawResult
                    _extractionState.value = ExtractionUiState.Loading(
                        "Site dinâmico detectado — executando motor JS automático…"
                    )
                    _headlessExtractUrl.value = normalizedUrl
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Direct HTTP extraction failed for $normalizedUrl; switching to automatic JS engine", e)
                _extractionState.value = ExtractionUiState.Loading(
                    "Acesso direto bloqueado — executando motor JS automático…"
                )
                _headlessExtractUrl.value = normalizedUrl
            }
        }
    }

    fun updateHeadlessStage(stageMessage: String) {
        if (_headlessExtractUrl.value != null) {
            _extractionState.value = ExtractionUiState.Loading(stageMessage)
        }
    }

    fun cancelHeadlessExtraction() {
        _headlessExtractUrl.value = null
        pendingFastPassFallback = null
        if (_extractionState.value is ExtractionUiState.Loading) {
            _extractionState.value = ExtractionUiState.Idle
        }
    }

    fun onHeadlessWebViewResult(
        sourceUrl: String,
        pageTitle: String,
        imageUrls: List<String>,
        chapterLinks: List<Pair<String, String>>
    ) {
        _headlessExtractUrl.value = null
        val fallback = pendingFastPassFallback
        pendingFastPassFallback = null

        activeExtractJob?.cancel()
        activeExtractJob = viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Organizando páginas capturadas…")
            try {
                val built = extractorEngine.buildResultFromWebViewExtraction(
                    sourceUrl = sourceUrl,
                    pageTitle = pageTitle,
                    imageUrls = imageUrls,
                    chapterLinks = chapterLinks
                )
                val mergedResult = if (built.detectedChapters.isEmpty() && fallback != null && fallback.detectedChapters.isNotEmpty()) {
                    built.copy(detectedChapters = fallback.detectedChapters)
                } else {
                    built
                }

                if (mergedResult.pages.isEmpty() && mergedResult.detectedChapters.isEmpty()) {
                    _extractionState.value = ExtractionUiState.Error(
                        "O site exigiu verificação humana ou carregamento interativo. Toque em Abrir no Navegador JS, aguarde as páginas aparecerem e toque em Capturar Imagens."
                    )
                    return@launch
                }
                finalizeExtraction(mergedResult)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Headless WebView extraction processing failed", e)
                _extractionState.value = ExtractionUiState.Error(
                    e.localizedMessage ?: "Falha ao processar imagens do motor JS."
                )
            }
        }
    }

    fun processWebViewExtraction(
        sourceUrl: String,
        pageTitle: String,
        imageUrls: List<String>,
        chapterLinks: List<Pair<String, String>>
    ) {
        _headlessExtractUrl.value = null
        pendingFastPassFallback = null
        _urlInput.value = sourceUrl
        activeExtractJob?.cancel()
        activeExtractJob = viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Processando imagens capturadas pelo navegador…")
            try {
                val rawResult = extractorEngine.buildResultFromWebViewExtraction(
                    sourceUrl = sourceUrl,
                    pageTitle = pageTitle,
                    imageUrls = imageUrls,
                    chapterLinks = chapterLinks
                )
                finalizeExtraction(rawResult)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "WebView extraction failed", e)
                _extractionState.value = ExtractionUiState.Error(
                    e.localizedMessage ?: "Falha ao processar imagens do navegador."
                )
            }
        }
    }

    private suspend fun finalizeExtraction(rawResult: ExtractionResult) {
        val prefs = preferences.value
        var finalResult = rawResult

        if (prefs.aiSmartFilterEnabled && rawResult.pages.isNotEmpty()) {
            _extractionState.value = ExtractionUiState.Loading(
                "Gemini Flash-Lite limpando anúncios e organizando capítulo…"
            )
            try {
                val aiFilter = aiRepository.filterExtractedPagesFast(
                    sourceUrl = rawResult.sourceUrl,
                    rawTitle = rawResult.seriesTitle,
                    candidates = rawResult.pages
                )
                val keptSet = aiFilter.keptIndices.toSet()
                val filteredPages = rawResult.pages
                    .filter { it.index in keptSet }
                    .mapIndexed { idx, item -> item.copy(index = idx, isAiVerified = true) }
                finalResult = rawResult.copy(
                    seriesTitle = aiFilter.seriesTitle,
                    chapterNumber = aiFilter.chapterNumber,
                    chapterLabel = "Capítulo ${aiFilter.chapterNumber}",
                    comicFormat = aiFilter.comicFormat,
                    pages = if (filteredPages.isNotEmpty()) filteredPages else rawResult.pages,
                    usedAiFilter = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AI fast filter fallback to heuristic extraction", e)
            }
        }

        if (finalResult.pages.isEmpty() && finalResult.detectedChapters.isEmpty()) {
            _extractionState.value = ExtractionUiState.Error(
                "Nenhuma página ou capítulo encontrado nesta URL. Tente o Modo Navegador JS."
            )
            return
        }

        val stored = libraryRepository.saveExtractionToLibrary(finalResult)
        _extractionState.value = ExtractionUiState.Success(
            chapterWithPages = stored,
            seriesTitle = finalResult.seriesTitle,
            comicFormatLabel = finalResult.comicFormat.label,
            detectedChapters = finalResult.detectedChapters,
            usedAiFilter = finalResult.usedAiFilter
        )

        if (prefs.autoDownloadOnExtract && stored.pages.isNotEmpty()) {
            downloadChapter(stored.chapter.id, finalResult.seriesTitle)
        }
    }

    fun toggleDetectedChapterSelection(url: String) {
        val current = _extractionState.value as? ExtractionUiState.Success ?: return
        val updated = current.detectedChapters.map { ch ->
            if (ch.url == url) ch.copy(selected = !ch.selected) else ch
        }
        _extractionState.value = current.copy(detectedChapters = updated)
    }

    fun selectAllDetectedChapters(selectAll: Boolean) {
        val current = _extractionState.value as? ExtractionUiState.Success ?: return
        _extractionState.value = current.copy(
            detectedChapters = current.detectedChapters.map { it.copy(selected = selectAll) }
        )
    }

    fun downloadChapter(chapterId: Long, seriesTitle: String) {
        activeDownloadJob?.cancel()
        activeDownloadJob = viewModelScope.launch {
            val prefs = preferences.value
            try {
                val status = downloadManager.downloadChapterOrganized(
                    chapterId = chapterId,
                    seriesTitle = seriesTitle,
                    saveToGalleryPictures = prefs.saveToGalleryPictures,
                    minWidthPx = prefs.minImageWidthPx,
                    onProgress = { progress ->
                        _downloadProgress.value = progress
                    }
                )
                refreshCurrentExtractionIfMatches(chapterId)
                val message = when (status) {
                    DownloadStatus.COMPLETED -> "Capítulo organizado salvo em $seriesTitle!"
                    DownloadStatus.PARTIAL -> "Download parcial concluído."
                    else -> "Não foi possível baixar as páginas deste capítulo."
                }
                _snackbarEvents.tryEmit(message)
            } catch (e: CancellationException) {
                _snackbarEvents.tryEmit("Download cancelado.")
                throw e
            } finally {
                _downloadProgress.value = null
            }
        }
    }

    fun downloadSelectedDetectedChapters() {
        val current = _extractionState.value as? ExtractionUiState.Success ?: return
        val selected = current.detectedChapters.filter { it.selected }
        if (selected.isEmpty()) {
            _snackbarEvents.tryEmit("Selecione pelo menos 1 capítulo para baixar.")
            return
        }
        activeDownloadJob?.cancel()
        activeDownloadJob = viewModelScope.launch {
            val prefs = preferences.value
            var completedCount = 0
            try {
                selected.forEachIndexed { idx, link ->
                    _batchProgress.value = BatchDownloadStatus(
                        currentChapterIndex = idx + 1,
                        totalChapters = selected.size,
                        currentChapterTitle = link.title
                    )
                    try {
                        val extracted = extractorEngine.extractFromUrl(link.url)
                        if (extracted.pages.isNotEmpty()) {
                            val stored = libraryRepository.saveExtractionToLibrary(
                                extracted.copy(seriesTitle = current.seriesTitle)
                            )
                            downloadManager.downloadChapterOrganized(
                                chapterId = stored.chapter.id,
                                seriesTitle = current.seriesTitle,
                                saveToGalleryPictures = prefs.saveToGalleryPictures,
                                minWidthPx = prefs.minImageWidthPx,
                                onProgress = { _downloadProgress.value = it }
                            )
                            completedCount++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping chapter ${link.url}", e)
                    }
                }
                _snackbarEvents.tryEmit("$completedCount de ${selected.size} capítulos baixados e organizados!")
            } finally {
                _batchProgress.value = null
                _downloadProgress.value = null
            }
        }
    }

    fun cancelActiveDownload() {
        activeDownloadJob?.cancel()
        _downloadProgress.value = null
        _batchProgress.value = null
    }

    private suspend fun refreshCurrentExtractionIfMatches(chapterId: Long) {
        val current = _extractionState.value as? ExtractionUiState.Success ?: return
        if (current.chapterWithPages.chapter.id == chapterId) {
            val chapter = libraryRepository.getChapterById(chapterId) ?: return
            val pages = libraryRepository.getPagesForChapter(chapterId)
            _extractionState.value = current.copy(
                chapterWithPages = ChapterWithPages(chapter, pages)
            )
        }
    }

    fun copyPageImage(page: PageEntity) {
        viewModelScope.launch {
            val ok = pageActionsHelper.copyPageImageToClipboard(page)
            if (ok) {
                libraryRepository.markPageCopied(page.id)
                refreshCurrentExtractionIfMatches(page.chapterId)
                _snackbarEvents.tryEmit("Página ${page.pageIndex + 1} copiada para a área de transferência!")
            } else {
                _snackbarEvents.tryEmit("Erro ao copiar imagem da página ${page.pageIndex + 1}.")
            }
        }
    }

    fun copyPageUrl(page: PageEntity) {
        pageActionsHelper.copyTextToClipboard("URL Página ${page.pageIndex + 1}", page.imageUrl)
        _snackbarEvents.tryEmit("Link da página ${page.pageIndex + 1} copiado!")
    }

    fun downloadSinglePage(page: PageEntity, chapter: ChapterEntity, seriesTitle: String) {
        viewModelScope.launch {
            val file = downloadManager.downloadSinglePage(
                page = page,
                chapter = chapter,
                seriesTitle = seriesTitle,
                saveToGalleryPictures = preferences.value.saveToGalleryPictures
            )
            refreshCurrentExtractionIfMatches(chapter.id)
            if (file != null) {
                _snackbarEvents.tryEmit("Página ${page.pageIndex + 1} salva: ${file.name}")
            } else {
                _snackbarEvents.tryEmit("Falha ao baixar página ${page.pageIndex + 1}.")
            }
        }
    }

    fun sharePage(page: PageEntity) {
        viewModelScope.launch {
            pageActionsHelper.sharePageImage(page)
        }
    }

    fun exportChapterAsCbz(pages: List<PageEntity>, outputUri: Uri, label: String) {
        viewModelScope.launch {
            val packed = downloadManager.exportChapterToCbzUri(pages, outputUri)
            if (packed > 0) {
                _snackbarEvents.tryEmit("$label exportado em .CBZ ($packed páginas)!")
            } else {
                _snackbarEvents.tryEmit("Baixe as páginas primeiro antes de exportar para .CBZ.")
            }
        }
    }

    fun deleteChapter(chapter: ChapterEntity) {
        viewModelScope.launch {
            libraryRepository.deleteChapter(chapter)
            _snackbarEvents.tryEmit("Capítulo removido.")
        }
    }

    fun deleteSeries(series: SeriesEntity) {
        viewModelScope.launch {
            libraryRepository.deleteSeries(series)
            _snackbarEvents.tryEmit("Obra \"${series.title}\" removida.")
        }
    }

    fun openReader(chapterId: Long, startIndex: Int = 0) {
        _readerInitialPage.value = startIndex
        _readerChapterId.value = chapterId
    }

    fun closeReader() {
        _readerChapterId.value = null
    }

    fun setAiMode(mode: AiAnalysisMode) {
        _aiMode.value = mode
    }

    fun loadPageForAiAnalysis(page: PageEntity, seriesLabel: String) {
        viewModelScope.launch {
            _aiBitmapLabel.value = "Carregando $seriesLabel • Pág ${page.pageIndex + 1}…"
            val bmp = pageActionsHelper.loadBitmapForAi(page)
            if (bmp != null) {
                _aiBitmap.value = bmp
                _aiBitmapLabel.value = "$seriesLabel • Página ${page.pageIndex + 1}"
            } else {
                _aiBitmapLabel.value = "Falha ao carregar imagem da página"
                _snackbarEvents.tryEmit("Não foi possível carregar a página para análise.")
            }
        }
    }

    fun loadPhotoPickerUriForAi(uri: Uri) {
        viewModelScope.launch {
            val bmp = pageActionsHelper.loadBitmapFromUri(uri)
            if (bmp != null) {
                _aiBitmap.value = bmp
                _aiBitmapLabel.value = "Captura enviada da galeria"
            } else {
                _snackbarEvents.tryEmit("Não foi possível abrir a imagem selecionada.")
            }
        }
    }

    fun loadPhotoPickerUriForStudio(uri: Uri) {
        viewModelScope.launch {
            val bmp = pageActionsHelper.loadBitmapFromUri(uri)
            if (bmp != null) {
                _studioReferenceBitmap.value = bmp
            } else {
                _snackbarEvents.tryEmit("Não foi possível carregar imagem de referência.")
            }
        }
    }

    fun clearStudioReference() {
        _studioReferenceBitmap.value = null
    }

    fun clearAiImage() {
        _aiBitmap.value = null
        _aiBitmapLabel.value = "Nenhuma página selecionada"
    }

    fun runAiAnalysis(prompt: String) {
        val mode = _aiMode.value
        val bitmap = _aiBitmap.value
        if (mode == AiAnalysisMode.VISION_FAST && bitmap == null) {
            _aiState.value = AiUiState.Error("Envie uma captura da galeria ou selecione uma página extraída.")
            return
        }
        val cleanPrompt = prompt.trim().ifBlank {
            when (mode) {
                AiAnalysisMode.VISION_FAST -> ""
                AiAnalysisMode.HIGH_THINKING ->
                    "Faça uma análise profunda da narrativa, motivações dos personagens, simbolismo visual e contexto cultural desta cena/obra."
                AiAnalysisMode.GENERAL_ASSISTANT ->
                    "Resuma esta cena/capítulo e sugira tags organizacionais para minha biblioteca."
            }
        }

        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch {
            _aiState.value = AiUiState.Loading
            try {
                val stream = when (mode) {
                    AiAnalysisMode.VISION_FAST -> aiRepository.analyzeComicPageStream(bitmap!!, cleanPrompt)
                    AiAnalysisMode.HIGH_THINKING -> aiRepository.analyzeDeepThinkingStream(bitmap, cleanPrompt)
                    AiAnalysisMode.GENERAL_ASSISTANT -> aiRepository.askGeneralAssistantStream(bitmap, cleanPrompt)
                }
                var lastText = ""
                stream.collect { partial ->
                    lastText = partial
                    _aiState.value = AiUiState.Streaming(partial)
                }
                _aiState.value = AiUiState.Success(lastText)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI analysis timed out", e)
                _aiState.value = AiUiState.Error("Tempo limite excedido (60s). Tente novamente.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PanelAiRepository.logAiFailure("runAiAnalysis", e)
                _aiState.value = AiUiState.Error(e.localizedMessage ?: "Falha na análise com Gemini.")
            }
        }
    }

    fun selectStudioAspectRatio(ratio: StudioAspectRatio) {
        _studioAspectRatio.value = ratio
    }

    fun generateStudioCover(prompt: String, useProQuality: Boolean) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            _studioState.value = StudioUiState.Error("Descreva a capa ou cena de manhwa/mangá que deseja gerar.")
            return
        }
        val ratio = _studioAspectRatio.value
        val reference = _studioReferenceBitmap.value

        viewModelScope.launch {
            _studioState.value = StudioUiState.Loading
            try {
                val bitmap = aiRepository.generateStudioArtwork(
                    prompt = cleanPrompt,
                    aspectRatio = ratio,
                    useProQuality = useProQuality,
                    referenceBitmap = reference
                )
                _studioState.value = StudioUiState.Success(
                    bitmap = bitmap,
                    aspectRatio = ratio,
                    prompt = cleanPrompt
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Image studio timed out", e)
                _studioState.value = StudioUiState.Error("Geração excedeu 60 segundos. Tente novamente.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PanelAiRepository.logAiFailure("generateStudioCover", e)
                _studioState.value = StudioUiState.Error(e.localizedMessage ?: "Falha ao gerar arte.")
            }
        }
    }

    fun copyStudioBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            pageActionsHelper.copyBitmapToClipboard(bitmap, "PanelPull Estúdio")
            _snackbarEvents.tryEmit("Arte copiada para a área de transferência!")
        }
    }

    fun exportApkToUri(outputUri: Uri) {
        viewModelScope.launch {
            _isExportingApk.value = true
            try {
                val bytes = apkExportHelper.exportApkToUri(outputUri)
                if (bytes > 0L) {
                    _snackbarEvents.tryEmit("Instalador ${installedApkInfo.suggestedFileName} salvo com sucesso!")
                } else {
                    _snackbarEvents.tryEmit("Não foi possível gravar o arquivo APK.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "APK export failed", e)
                _snackbarEvents.tryEmit(e.localizedMessage ?: "Erro ao salvar o APK.")
            } finally {
                _isExportingApk.value = false
            }
        }
    }

    fun shareAppApk() {
        viewModelScope.launch {
            _isExportingApk.value = true
            try {
                val shared = apkExportHelper.shareInstalledApk()
                if (!shared) {
                    _snackbarEvents.tryEmit("Pacote APK não disponível para compartilhamento.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "APK share failed", e)
                _snackbarEvents.tryEmit(e.localizedMessage ?: "Erro ao compartilhar o APK.")
            } finally {
                _isExportingApk.value = false
            }
        }
    }
}
