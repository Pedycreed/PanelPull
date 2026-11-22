package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.local.ChapterWithPages
import com.example.data.local.PageEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReaderDialog(
    chapterWithPages: ChapterWithPages,
    initialPageIndex: Int,
    onDismiss: () -> Unit,
    onCopyPage: (PageEntity) -> Unit,
    onDownloadPage: (PageEntity) -> Unit,
    onSharePage: (PageEntity) -> Unit,
    onAnalyzePage: (PageEntity) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val pages = remember(chapterWithPages.pages) {
        chapterWithPages.pages.sortedBy { it.pageIndex }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    )
    var webtoonSeamless by remember { mutableStateOf(true) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    val currentVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)) }
    }
    val activePage = pages.getOrNull(currentVisibleIndex)
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color(0xFF07070B),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xCC0D0C14),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Column {
                        Text(
                            text = chapterWithPages.chapter.chapterLabel,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Página ${currentVisibleIndex + 1} de ${pages.size} • Zoom ${(zoomScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB8B3CC)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("reader_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar do leitor"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { webtoonSeamless = !webtoonSeamless },
                        modifier = Modifier.testTag("reader_mode_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Alternar modo Webtoon contínuo ou espaçado"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (activePage != null) {
                Surface(
                    color = Color(0xEB15141F),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pág. ${(activePage.pageIndex + 1).toString().padStart(3, '0')}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(
                                onClick = { onCopyPage(activePage) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("reader_copy_page_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar página atual")
                            }
                            FilledTonalIconButton(
                                onClick = { onDownloadPage(activePage) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Baixar página atual")
                            }
                            FilledTonalIconButton(
                                onClick = { onSharePage(activePage) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Compartilhar página")
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    onAnalyzePage(activePage)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("reader_analyze_page_button")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Analisar página com IA")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 3f)
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale
                    ),
                contentPadding = PaddingValues(vertical = if (webtoonSeamless) 0.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (webtoonSeamless) 0.dp else 12.dp)
            ) {
                itemsIndexed(pages, key = { _, page -> page.id }) { _, page ->
                    val localFile = page.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                    val request = ImageRequest.Builder(context)
                        .data(localFile ?: page.imageUrl)
                        .addHeader("Referer", page.refererUrl)
                        .addHeader("Origin", com.example.data.extractor.WebSessionCookieStore.originOf(page.refererUrl))
                        .addHeader("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
                        .apply {
                            com.example.data.extractor.WebSessionCookieStore.getCookieForUrl(page.imageUrl)?.let { cookie ->
                                addHeader("Cookie", cookie)
                            }
                        }
                        .crossfade(true)
                        .build()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D0C14))
                    ) {
                        AsyncImage(
                            model = request,
                            contentDescription = "Página ${page.pageIndex + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!webtoonSeamless) {
                            Surface(
                                color = Color(0xB30D0C14),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${(page.pageIndex + 1).toString().padStart(3, '0')}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onCopyPage(page) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copiar página ${page.pageIndex + 1}",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
