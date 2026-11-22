package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.components.InstallAppSheet
import com.example.ui.components.PageReaderDialog
import com.example.ui.components.WebViewExtractorDialog
import com.example.ui.screens.AiAnalyzerScreen
import com.example.ui.screens.CoverStudioScreen
import com.example.ui.screens.ExtractorScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.PanelPullTheme
import kotlinx.coroutines.launch

const val ROUTE_EXTRACTOR = "extractor"
const val ROUTE_LIBRARY = "library"
const val ROUTE_AI_ANALYZER = "ai_analyzer"
const val ROUTE_COVER_STUDIO = "cover_studio"

private data class NavTabItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navTabs = listOf(
    NavTabItem(
        route = ROUTE_EXTRACTOR,
        labelRes = R.string.tab_extractor,
        selectedIcon = Icons.Filled.DownloadForOffline,
        unselectedIcon = Icons.Outlined.DownloadForOffline,
        testTag = "nav_tab_extractor"
    ),
    NavTabItem(
        route = ROUTE_LIBRARY,
        labelRes = R.string.tab_library,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark,
        testTag = "nav_tab_library"
    ),
    NavTabItem(
        route = ROUTE_AI_ANALYZER,
        labelRes = R.string.tab_ai_analyzer,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_tab_ai_analyzer"
    ),
    NavTabItem(
        route = ROUTE_COVER_STUDIO,
        labelRes = R.string.tab_cover_studio,
        selectedIcon = Icons.Filled.Palette,
        unselectedIcon = Icons.Outlined.Palette,
        testTag = "nav_tab_cover_studio"
    )
)

@Composable
fun PanelPullApp(
    sharedInitialUrl: String? = null,
    viewModel: MainViewModel = viewModel()
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val extractionState by viewModel.extractionState.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val aiBitmap by viewModel.aiBitmap.collectAsStateWithLifecycle()
    val aiBitmapLabel by viewModel.aiBitmapLabel.collectAsStateWithLifecycle()
    val aiMode by viewModel.aiMode.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val studioRatio by viewModel.studioAspectRatio.collectAsStateWithLifecycle()
    val studioReference by viewModel.studioReferenceBitmap.collectAsStateWithLifecycle()
    val studioState by viewModel.studioState.collectAsStateWithLifecycle()
    val readerChapterId by viewModel.readerChapterId.collectAsStateWithLifecycle()
    val readerInitialPage by viewModel.readerInitialPage.collectAsStateWithLifecycle()
    val isExportingApk by viewModel.isExportingApk.collectAsStateWithLifecycle()

    var showWebViewExtractor by remember { mutableStateOf(false) }
    var showInstallAppSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_EXTRACTOR

    LaunchedEffect(sharedInitialUrl) {
        if (!sharedInitialUrl.isNullOrBlank()) {
            viewModel.updateUrlInput(sharedInitialUrl)
            viewModel.extractFromUrl(sharedInitialUrl)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    PanelPullTheme(darkTheme = preferences.darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useNavigationRail && readerChapterId == null && !showWebViewExtractor) {
                        NavigationBar {
                            navTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                val label = stringResource(tab.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label, maxLines = 1) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail && readerChapterId == null && !showWebViewExtractor) {
                        NavigationRail {
                            navTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                val label = stringResource(tab.labelRes)
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label, maxLines = 1) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_EXTRACTOR,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(ROUTE_EXTRACTOR) {
                            ExtractorScreen(
                                urlInput = urlInput,
                                extractionState = extractionState,
                                preferences = preferences,
                                downloadProgress = downloadProgress,
                                batchProgress = batchProgress,
                                onUrlChange = viewModel::updateUrlInput,
                                onExtractClick = { viewModel.extractFromUrl() },
                                onOpenWebViewClick = { showWebViewExtractor = true },
                                onToggleAutoDownload = viewModel::setAutoDownload,
                                onToggleAiFilter = viewModel::setAiSmartFilter,
                                onToggleSaveGallery = viewModel::setSaveToGallery,
                                onToggleTheme = viewModel::setDarkTheme,
                                onDownloadChapter = viewModel::downloadChapter,
                                onCancelDownload = viewModel::cancelActiveDownload,
                                onToggleChapterSelection = viewModel::toggleDetectedChapterSelection,
                                onSelectAllChapters = viewModel::selectAllDetectedChapters,
                                onDownloadSelectedChapters = viewModel::downloadSelectedDetectedChapters,
                                onCopyPage = viewModel::copyPageImage,
                                onCopyPageUrl = viewModel::copyPageUrl,
                                onDownloadSinglePage = viewModel::downloadSinglePage,
                                onAnalyzePage = { page, title ->
                                    viewModel.loadPageForAiAnalysis(page, title)
                                    navigateToTab(ROUTE_AI_ANALYZER)
                                },
                                onOpenReader = viewModel::openReader,
                                onOpenInstallAppSheet = { showInstallAppSheet = true }
                            )
                        }

                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                library = library,
                                onOpenReader = { chId -> viewModel.openReader(chId, 0) },
                                onDownloadChapter = viewModel::downloadChapter,
                                onExportCbz = viewModel::exportChapterAsCbz,
                                onDeleteChapter = viewModel::deleteChapter,
                                onDeleteSeries = viewModel::deleteSeries,
                                onGoToExtractor = { navigateToTab(ROUTE_EXTRACTOR) }
                            )
                        }

                        composable(ROUTE_AI_ANALYZER) {
                            AiAnalyzerScreen(
                                selectedBitmap = aiBitmap,
                                bitmapLabel = aiBitmapLabel,
                                selectedMode = aiMode,
                                aiState = aiState,
                                onSelectMode = viewModel::setAiMode,
                                onUploadPhotoUri = viewModel::loadPhotoPickerUriForAi,
                                onClearImage = viewModel::clearAiImage,
                                onAnalyzeClick = viewModel::runAiAnalysis,
                                onShowMessage = { msg ->
                                    coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }

                        composable(ROUTE_COVER_STUDIO) {
                            CoverStudioScreen(
                                selectedRatio = studioRatio,
                                referenceBitmap = studioReference,
                                studioState = studioState,
                                onSelectRatio = viewModel::selectStudioAspectRatio,
                                onPickReferenceUri = viewModel::loadPhotoPickerUriForStudio,
                                onClearReference = viewModel::clearStudioReference,
                                onGenerateClick = viewModel::generateStudioCover,
                                onCopyArtwork = viewModel::copyStudioBitmap
                            )
                        }
                    }
                }
            }

            // Fullscreen Webtoon / Page Reader Overlay
            val openChapterId = readerChapterId
            if (openChapterId != null) {
                val chapterFlow = remember(openChapterId) { viewModel.observeChapter(openChapterId) }
                val chapterWithPages by chapterFlow.collectAsStateWithLifecycle(initialValue = null)
                val resolvedChapter = chapterWithPages
                if (resolvedChapter != null) {
                    val seriesName = library
                        .firstOrNull { s -> s.chapters.any { it.chapter.id == resolvedChapter.chapter.id } }
                        ?.series?.title ?: "PanelPull"
                    PageReaderDialog(
                        chapterWithPages = resolvedChapter,
                        initialPageIndex = readerInitialPage,
                        onDismiss = viewModel::closeReader,
                        onCopyPage = viewModel::copyPageImage,
                        onDownloadPage = { page ->
                            viewModel.downloadSinglePage(page, resolvedChapter.chapter, seriesName)
                        },
                        onSharePage = viewModel::sharePage,
                        onAnalyzePage = { page ->
                            viewModel.loadPageForAiAnalysis(page, seriesName)
                            navigateToTab(ROUTE_AI_ANALYZER)
                        }
                    )
                }
            }

            // In-App JS WebView Extractor Overlay
            if (showWebViewExtractor) {
                WebViewExtractorDialog(
                    initialUrl = urlInput,
                    onDismiss = { showWebViewExtractor = false },
                    onExtracted = { sourceUrl, pageTitle, imageUrls, chapterLinks ->
                        showWebViewExtractor = false
                        viewModel.processWebViewExtraction(
                            sourceUrl = sourceUrl,
                            pageTitle = pageTitle,
                            imageUrls = imageUrls,
                            chapterLinks = chapterLinks
                        )
                    }
                )
            }

            if (showInstallAppSheet) {
                InstallAppSheet(
                    apkInfo = viewModel.installedApkInfo,
                    isExporting = isExportingApk,
                    onDismiss = { showInstallAppSheet = false },
                    onExportApkToUri = { uri ->
                        viewModel.exportApkToUri(uri)
                        showInstallAppSheet = false
                    },
                    onShareApk = {
                        viewModel.shareAppApk()
                        showInstallAppSheet = false
                    }
                )
            }
        }
    }
}
