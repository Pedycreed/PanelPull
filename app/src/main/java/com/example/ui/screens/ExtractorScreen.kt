package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.R
import com.example.data.extractor.ComicImageLoader
import com.example.data.extractor.DownloadProgress
import com.example.data.local.ChapterEntity
import com.example.data.local.PageEntity
import com.example.data.prefs.UserPreferences
import com.example.ui.BatchDownloadStatus
import com.example.ui.ExtractionUiState
import com.example.ui.theme.EmeraldSuccess
import java.io.File

@Composable
fun ExtractorScreen(
    urlInput: String,
    extractionState: ExtractionUiState,
    preferences: UserPreferences,
    downloadProgress: DownloadProgress?,
    batchProgress: BatchDownloadStatus?,
    onUrlChange: (String) -> Unit,
    onExtractClick: () -> Unit,
    onOpenWebViewClick: () -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onToggleAiFilter: (Boolean) -> Unit,
    onToggleSaveGallery: (Boolean) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onDownloadChapter: (chapterId: Long, seriesTitle: String) -> Unit,
    onCancelDownload: () -> Unit,
    onToggleChapterSelection: (String) -> Unit,
    onSelectAllChapters: (Boolean) -> Unit,
    onDownloadSelectedChapters: () -> Unit,
    onCopyPage: (PageEntity) -> Unit,
    onCopyPageUrl: (PageEntity) -> Unit,
    onDownloadSinglePage: (PageEntity, ChapterEntity, String) -> Unit,
    onAnalyzePage: (PageEntity, String) -> Unit,
    onOpenReader: (chapterId: Long, pageIndex: Int) -> Unit,
    onOpenInstallAppSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var isGridView by remember { mutableStateOf(true) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Editorial Studio Hero Banner
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_hero_ink_studio_1795118347993),
                            contentDescription = "Banner estúdio PanelPull",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0x550D0C14),
                                            Color(0xE80D0C14)
                                        )
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "PANELPULL",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Surface(
                                        color = Color(0x991E1D2B),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Text(
                                            text = "MANHWA • MANGÁ • WEBTOON",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF38BDF8),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Extrator de Páginas & Capítulos",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = stringResource(R.string.extractor_hero_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFD5D1E4),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier.align(Alignment.Top),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    onClick = onOpenInstallAppSheet,
                                    color = Color(0xCC1E1D2B),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0x8838BDF8)),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("open_install_app_sheet_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Android,
                                            contentDescription = "Baixar APK do app para instalar no Android",
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "APK",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onToggleTheme(!preferences.darkTheme) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("theme_toggle_button")
                                ) {
                                    Icon(
                                        imageVector = if (preferences.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = "Alternar tema claro/escuro",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. URL Input & Extraction Controls
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = onUrlChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            label = { Text("URL do Capítulo ou Obra") },
                            placeholder = { Text(stringResource(R.string.url_input_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    keyboard?.hide()
                                    onExtractClick()
                                }
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null)
                            },
                            trailingIcon = {
                                Row {
                                    if (urlInput.isNotEmpty()) {
                                        IconButton(onClick = { onUrlChange("") }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpar URL")
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                            if (clip.isNotBlank()) onUrlChange(clip.trim())
                                        },
                                        modifier = Modifier.testTag("paste_url_button")
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Colar URL")
                                    }
                                }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    keyboard?.hide()
                                    onExtractClick()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                                    .testTag("extract_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_extract),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            FilledTonalButton(
                                onClick = {
                                    keyboard?.hide()
                                    onOpenWebViewClick()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("webview_mode_button")
                            ) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.action_webview_mode),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Quick automation switches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.toggle_auto_download),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Baixa e organiza pastas automaticamente ao extrair",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preferences.autoDownloadOnExtract,
                                onCheckedChange = onToggleAutoDownload,
                                modifier = Modifier.testTag("auto_download_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showAdvancedSettings = !showAdvancedSettings },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("toggle_advanced_options")
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (showAdvancedSettings) "Ocultar opções" else "Filtros IA & Galeria",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (preferences.aiSmartFilterEnabled) {
                                AssistChip(
                                    onClick = { showAdvancedSettings = !showAdvancedSettings },
                                    label = {
                                        Text(
                                            text = "IA Flash",
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(visible = showAdvancedSettings) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.toggle_ai_filter),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "Usa gemini-3.1-flash-lite para remover anúncios e banners",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = preferences.aiSmartFilterEnabled,
                                        onCheckedChange = onToggleAiFilter
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.toggle_save_gallery),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "Espelha pastas Obra/Capítulo na galeria pública",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = preferences.saveToGalleryPictures,
                                        onCheckedChange = onToggleSaveGallery
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Active Download Progress Card
            if (downloadProgress != null || batchProgress != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = batchProgress?.let {
                                        "Lote ${it.currentChapterIndex}/${it.totalChapters}: ${it.currentChapterTitle}"
                                    } ?: "Baixando ${downloadProgress?.chapterLabel.orEmpty()}…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = onCancelDownload) {
                                    Text("Cancelar")
                                }
                            }
                            if (downloadProgress != null && downloadProgress.totalPages > 0) {
                                val fraction = downloadProgress.completedPages.toFloat() / downloadProgress.totalPages
                                LinearProgressIndicator(
                                    progress = { fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "${downloadProgress.completedPages} / ${downloadProgress.totalPages} páginas organizadas",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            // 4. Extraction States
            when (val state = extractionState) {
                is ExtractionUiState.Idle -> {
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_pages_1795118341482),
                                    contentDescription = "Ilustração de páginas vazias",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .aspectRatio(4f / 3f)
                                        .clip(MaterialTheme.shapes.medium)
                                )
                                Text(
                                    text = "Pronto para extrair",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Cole o link de qualquer capítulo ou obra (incluindo Comix.to, Webtoons, mangás e sites Next.js/SPA). Se o site exigir verificação ou carregamento dinâmico, use o botão Navegador JS.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is ExtractionUiState.Loading -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Extraindo páginas…",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = state.stageMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = onOpenWebViewClick) {
                                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Abrir Navegador JS")
                                    }
                                }
                            }
                        }
                    }
                }

                is ExtractionUiState.Error -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Não foi possível extrair diretamente",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                FilledTonalButton(onClick = onOpenWebViewClick) {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Abrir no Navegador JS")
                                }
                            }
                        }
                    }
                }

                is ExtractionUiState.Success -> {
                    val chapter = state.chapterWithPages.chapter
                    val pages = state.chapterWithPages.pages.sortedBy { it.pageIndex }

                    // Detected Chapter Links (Series Index Batch Downloader)
                    if (state.detectedChapters.isNotEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Capítulos Detectados (${state.detectedChapters.size})",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = "Baixe múltiplos capítulos em pastas organizadas",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        val allSelected = state.detectedChapters.all { it.selected }
                                        TextButton(onClick = { onSelectAllChapters(!allSelected) }) {
                                            Text(if (allSelected) "Desmarcar" else "Marcar todos")
                                        }
                                    }

                                    state.detectedChapters.take(8).forEach { link ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onToggleChapterSelection(link.url) }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = link.selected,
                                                onCheckedChange = { onToggleChapterSelection(link.url) }
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = link.title,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Cap. ${link.chapterNumber}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }

                                    val selectedCount = state.detectedChapters.count { it.selected }
                                    Button(
                                        onClick = onDownloadSelectedChapters,
                                        enabled = selectedCount > 0 && downloadProgress == null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("batch_download_button")
                                    ) {
                                        Icon(Icons.Default.FolderZip, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Baixar $selectedCount Capítulos Organizados")
                                    }
                                }
                            }
                        }
                    }

                    // Chapter Summary & Organized Download Header
                    if (pages.isNotEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = state.comicFormatLabel.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                if (state.usedAiFilter) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "IA FILTRADO",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = state.seriesTitle,
                                                style = MaterialTheme.typography.titleLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${chapter.chapterLabel} • ${pages.size} páginas • ${chapter.downloadedCount} salvas",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Row {
                                            IconButton(
                                                onClick = { isGridView = !isGridView },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("toggle_view_mode_button")
                                            ) {
                                                Icon(
                                                    imageVector = if (isGridView) Icons.Default.ViewDay else Icons.Default.GridView,
                                                    contentDescription = "Alternar Grade ou Lista Webtoon"
                                                )
                                            }
                                            IconButton(
                                                onClick = { onOpenReader(chapter.id, 0) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("open_reader_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Fullscreen,
                                                    contentDescription = "Abrir leitor tela cheia"
                                                )
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = { onDownloadChapter(chapter.id, state.seriesTitle) },
                                        enabled = downloadProgress == null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("download_chapter_button")
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("${stringResource(R.string.action_download_chapter)} (${pages.size})")
                                    }
                                }
                            }
                        }

                        if (isGridView) {
                            val rows = pages.chunked(2)
                            items(rows, key = { row -> row.first().id }) { rowPages ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowPages.forEach { page ->
                                        ExtractedPageCard(
                                            page = page,
                                            isCompact = true,
                                            onOpenReader = { onOpenReader(chapter.id, page.pageIndex) },
                                            onCopyPage = { onCopyPage(page) },
                                            onCopyLink = { onCopyPageUrl(page) },
                                            onDownloadPage = { onDownloadSinglePage(page, chapter, state.seriesTitle) },
                                            onAnalyzePage = { onAnalyzePage(page, state.seriesTitle) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (rowPages.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            items(pages, key = { it.id }) { page ->
                                ExtractedPageCard(
                                    page = page,
                                    isCompact = false,
                                    onOpenReader = { onOpenReader(chapter.id, page.pageIndex) },
                                    onCopyPage = { onCopyPage(page) },
                                    onCopyLink = { onCopyPageUrl(page) },
                                    onDownloadPage = { onDownloadSinglePage(page, chapter, state.seriesTitle) },
                                    onAnalyzePage = { onAnalyzePage(page, state.seriesTitle) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ExtractedPageCard(
    page: PageEntity,
    isCompact: Boolean,
    onOpenReader: () -> Unit,
    onCopyPage: () -> Unit,
    onCopyLink: () -> Unit,
    onDownloadPage: () -> Unit,
    onAnalyzePage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { ComicImageLoader.getImageLoader(context) }
    var retryKey by remember(page.imageUrl) { mutableIntStateOf(0) }
    val localFile = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
    val imageRequest = remember(page.imageUrl, localFile?.absolutePath, retryKey) {
        ComicImageLoader.buildImageRequest(
            context = context,
            localFile = localFile,
            imageUrl = page.imageUrl,
            refererUrl = page.refererUrl,
            retryKey = retryKey
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCompact) Modifier.aspectRatio(0.72f) else Modifier.height(340.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clickable(onClick = onOpenReader)
            ) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    imageLoader = imageLoader,
                    contentDescription = "Página extraída ${page.pageIndex + 1}",
                    contentScale = if (isCompact) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(26.dp)
                                )
                                Text(
                                    text = "Carregando…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { retryKey++ }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BrokenImage,
                                    contentDescription = "Falha ao carregar imagem",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "Falha na imagem",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(
                                    onClick = { retryKey++ },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Recarregar",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                )

                Surface(
                    color = Color(0xCC0D0C14),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "#${(page.pageIndex + 1).toString().padStart(3, '0')}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                if (page.isDownloaded || page.isCopied) {
                    Surface(
                        color = Color(0xCC0D0C14),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Salva/Copiada",
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (page.isDownloaded) "SALVA" else "COPIADA",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldSuccess
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = if (isCompact) Arrangement.SpaceEvenly else Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCompact) {
                    FilledTonalIconButton(
                        onClick = onCopyPage,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("copy_page_${page.pageIndex}")
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copiar imagem da página ${page.pageIndex + 1}"
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onCopyPage,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("copy_page_${page.pageIndex}")
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_copy_page),
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onDownloadPage,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("download_page_${page.pageIndex}")
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Baixar página ${page.pageIndex + 1}"
                    )
                }

                FilledTonalIconButton(
                    onClick = onAnalyzePage,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("analyze_page_${page.pageIndex}")
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Analisar página ${page.pageIndex + 1} com IA"
                    )
                }

                if (!isCompact) {
                    FilledTonalIconButton(
                        onClick = onCopyLink,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Copiar URL da página ${page.pageIndex + 1}"
                        )
                    }
                }
            }
        }
    }
}
