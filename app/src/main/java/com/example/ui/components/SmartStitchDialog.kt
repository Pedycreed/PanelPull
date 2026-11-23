package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.extractor.ComicImageLoader
import com.example.data.local.ChapterEntity
import com.example.data.local.PageEntity
import com.example.data.stitch.SmartStitchConfig
import com.example.data.stitch.StitchDetectorMode
import com.example.data.stitch.StitchOutputFormat
import com.example.data.stitch.StitchProgress
import com.example.data.stitch.WidthEnforcementMode
import com.example.ui.SmartStitchTarget
import com.example.ui.SmartStitchUiState
import com.example.ui.theme.EmeraldSuccess
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartStitchDialog(
    target: SmartStitchTarget,
    stitchState: SmartStitchUiState,
    defaultSaveToGallery: Boolean,
    onDismiss: () -> Unit,
    onSelectGalleryUris: (List<Uri>) -> Unit,
    onRunStitch: (SmartStitchConfig) -> Unit,
    onCancelStitch: () -> Unit,
    onOpenStitchedReader: (chapterId: Long, pageIndex: Int) -> Unit,
    onCopyStitchedPage: (PageEntity) -> Unit,
    onDownloadStitchedPage: (PageEntity, ChapterEntity, String) -> Unit,
    onExportStitchedPageToUri: (PageEntity, Uri, String) -> Unit,
    onShareStitchedPage: (PageEntity) -> Unit,
    onExportStitchedCbz: (pages: List<PageEntity>, outputUri: Uri, label: String) -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val imageLoader = remember(context) { ComicImageLoader.getImageLoader(context) }
    val listState = rememberLazyListState()

    var config by remember(defaultSaveToGallery) {
        mutableStateOf(SmartStitchConfig(saveToGallery = defaultSaveToGallery))
    }
    var customWidthText by remember { mutableStateOf(config.customWidthPx.toString()) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showSettingsWhenDone by remember { mutableStateOf(false) }

    LaunchedEffect(stitchState) {
        when (stitchState) {
            is SmartStitchUiState.Running,
            is SmartStitchUiState.Error -> {
                listState.animateScrollToItem(0)
            }
            is SmartStitchUiState.Success -> {
                showSettingsWhenDone = false
                listState.animateScrollToItem(0)
            }
            is SmartStitchUiState.Idle -> {
                showSettingsWhenDone = true
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onSelectGalleryUris(uris)
        }
    }

    var pendingCbzChapter by remember { mutableStateOf<Pair<String, List<PageEntity>>?>(null) }
    val cbzExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-cbz")
    ) { uri ->
        val pending = pendingCbzChapter
        if (uri != null && pending != null) {
            onExportStitchedCbz(pending.second, uri, pending.first)
        }
        pendingCbzChapter = null
    }

    var pendingSingleStrip by remember { mutableStateOf<Pair<PageEntity, String>?>(null) }
    val singleStripSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val pending = pendingSingleStrip
        if (uri != null && pending != null) {
            onExportStitchedPageToUri(pending.first, uri, pending.second)
        }
        pendingSingleStrip = null
    }

    var downloadedStripIds by remember(stitchState) { mutableStateOf<Set<Long>>(emptySet()) }

    val isRunning = stitchState is SmartStitchUiState.Running
    val isSuccess = stitchState is SmartStitchUiState.Success

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SmartStitch",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Juntar páginas sem cortar balões ou quadros",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("smartstitch_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Source Card
                item(key = "source_card") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (target) {
                                        is SmartStitchTarget.Chapter -> target.seriesTitle
                                        is SmartStitchTarget.GalleryImages -> "Imagens da Galeria"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when (target) {
                                        is SmartStitchTarget.Chapter ->
                                            "${target.chapterWithPages.chapter.chapterLabel} • ${target.chapterWithPages.pages.size} páginas"
                                        is SmartStitchTarget.GalleryImages ->
                                            if (target.uris.isEmpty()) {
                                                "Toque em Galeria para selecionar imagens"
                                            } else {
                                                "${target.uris.size} imagens selecionadas do aparelho"
                                            }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    galleryPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !isRunning,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .testTag("smartstitch_pick_gallery_button")
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Galeria", maxLines = 1)
                            }
                        }
                    }
                }

                // 2. Live Progress / Error / Results placed IMMEDIATELY AT THE TOP so they are never offscreen
                when (val state = stitchState) {
                    is SmartStitchUiState.Idle -> Unit

                    is SmartStitchUiState.Running -> {
                        item(key = "running_card") {
                            StitchProgressCard(
                                progress = state.progress,
                                onCancel = onCancelStitch
                            )
                        }
                    }

                    is SmartStitchUiState.Error -> {
                        item(key = "error_card") {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    is SmartStitchUiState.Success -> {
                        val stitchedPages = state.chapterWithPages.pages.sortedBy { it.pageIndex }
                        item(key = "success_banner") {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldSuccess
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (state.restoredFromLibrary) {
                                                    "SmartStitch salvo na Biblioteca"
                                                } else {
                                                    "SmartStitch concluído e salvo!"
                                                },
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            val gallerySuffix = if (state.gallerySavedCount > 0) {
                                                " • +${state.gallerySavedCount} na Galeria"
                                            } else {
                                                ""
                                            }
                                            Text(
                                                text = "${state.inputPageCount} páginas → ${stitchedPages.size} tiras (${state.targetWidth}px)$gallerySuffix",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                onOpenStitchedReader(state.chapterWithPages.chapter.id, 0)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("smartstitch_open_reader_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Abrir Leitor", maxLines = 1)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val safeName = "${state.seriesTitle.replace(" ", "_")}_SmartStitch.cbz"
                                                pendingCbzChapter = "${state.seriesTitle} • SmartStitch" to stitchedPages
                                                cbzExportLauncher.launch(safeName)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                        ) {
                                            Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Export .CBZ", maxLines = 1)
                                        }
                                    }

                                    TextButton(
                                        onClick = { showSettingsWhenDone = !showSettingsWhenDone },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (showSettingsWhenDone) "Ocultar parâmetros" else "Ajustar parâmetros e refazer"
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = if (showSettingsWhenDone) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Settings Section (always expanded unless completed results are showing and user collapsed it)
                if (!isSuccess || showSettingsWhenDone) {
                    item(key = "presets_row") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Predefinições rápidas",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = config.detectorMode == StitchDetectorMode.SMART_PIXEL &&
                                            config.splitHeight == 5000 &&
                                            config.widthEnforcement == WidthEnforcementMode.AUTOMATIC,
                                        onClick = {
                                            config = config.copy(
                                                detectorMode = StitchDetectorMode.SMART_PIXEL,
                                                splitHeight = 5000,
                                                sensitivity = 90,
                                                widthEnforcement = WidthEnforcementMode.AUTOMATIC
                                            )
                                        },
                                        label = { Text("Manhwa Padrão (5000px)", maxLines = 1) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = config.detectorMode == StitchDetectorMode.SMART_PIXEL &&
                                            config.splitHeight == 3000 &&
                                            config.widthEnforcement == WidthEnforcementMode.CUSTOM &&
                                            config.customWidthPx == 720,
                                        onClick = {
                                            customWidthText = "720"
                                            config = config.copy(
                                                detectorMode = StitchDetectorMode.SMART_PIXEL,
                                                splitHeight = 3000,
                                                sensitivity = 90,
                                                widthEnforcement = WidthEnforcementMode.CUSTOM,
                                                customWidthPx = 720
                                            )
                                        },
                                        label = { Text("Leve 720p (3000px)", maxLines = 1) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = config.detectorMode == StitchDetectorMode.SINGLE_STRIP,
                                        onClick = {
                                            config = config.copy(
                                                detectorMode = StitchDetectorMode.SINGLE_STRIP,
                                                widthEnforcement = WidthEnforcementMode.AUTOMATIC
                                            )
                                        },
                                        label = { Text("Tira Única Contínua", maxLines = 1) }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "settings_card") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Detection Mode
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Modo de Detecção",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(StitchDetectorMode.entries) { mode ->
                                            FilterChip(
                                                selected = config.detectorMode == mode,
                                                onClick = { config = config.copy(detectorMode = mode) },
                                                label = { Text(mode.label, maxLines = 1) }
                                            )
                                        }
                                    }
                                    Text(
                                        text = config.detectorMode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Split Height Slider
                                if (config.detectorMode != StitchDetectorMode.SINGLE_STRIP) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Altura de Corte (Split Height)",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "${config.splitHeight} px",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = config.splitHeight.toFloat(),
                                            onValueChange = { value ->
                                                val snapped = (value / 500f).roundToInt() * 500
                                                config = config.copy(splitHeight = snapped.coerceIn(1500, 15000))
                                            },
                                            valueRange = 1500f..15000f,
                                            steps = 26,
                                            enabled = !isRunning
                                        )
                                    }
                                }

                                // Sensitivity Slider (PixelComparisonDetector)
                                if (config.detectorMode == StitchDetectorMode.SMART_PIXEL) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Sensibilidade (Pixel Detector)",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "${config.sensitivity}%",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = config.sensitivity.toFloat(),
                                            onValueChange = { value ->
                                                config = config.copy(sensitivity = value.roundToInt().coerceIn(0, 100))
                                            },
                                            valueRange = 0f..100f,
                                            enabled = !isRunning
                                        )
                                        Text(
                                            text = "Padrão 90%: quanto maior, mais rigoroso para evitar cortar balões de fala ou arte.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                // Width Enforcement Mode
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Ajuste de Largura (Width Enforcement)",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(WidthEnforcementMode.entries) { mode ->
                                            FilterChip(
                                                selected = config.widthEnforcement == mode,
                                                onClick = { config = config.copy(widthEnforcement = mode) },
                                                label = { Text(mode.label, maxLines = 1) }
                                            )
                                        }
                                    }
                                    Text(
                                        text = config.widthEnforcement.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (config.widthEnforcement == WidthEnforcementMode.CUSTOM) {
                                        OutlinedTextField(
                                            value = customWidthText,
                                            onValueChange = { raw ->
                                                val digits = raw.filter { it.isDigit() }.take(4)
                                                customWidthText = digits
                                                digits.toIntOrNull()?.let { px ->
                                                    config = config.copy(customWidthPx = px.coerceIn(200, 2000))
                                                }
                                            },
                                            label = { Text("Largura personalizada (px)") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                // Output Format
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Formato de Saída",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StitchOutputFormat.entries.forEach { fmt ->
                                            FilterChip(
                                                selected = config.outputFormat == fmt,
                                                onClick = { config = config.copy(outputFormat = fmt) },
                                                label = { Text(fmt.label, maxLines = 1) }
                                            )
                                        }
                                    }
                                    if (config.outputFormat != StitchOutputFormat.PNG) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Qualidade de compressão",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "${config.compressionQuality}%",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = config.compressionQuality.toFloat(),
                                            onValueChange = { v ->
                                                config = config.copy(compressionQuality = v.roundToInt().coerceIn(60, 100))
                                            },
                                            valueRange = 60f..100f,
                                            enabled = !isRunning
                                        )
                                    }
                                }

                                // Advanced toggle
                                TextButton(
                                    onClick = { showAdvanced = !showAdvanced },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Parâmetros avançados (Scan Step & Margens)")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                AnimatedVisibility(visible = showAdvanced) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Passo de Varredura (Scan Step)", style = MaterialTheme.typography.bodyMedium)
                                            Text("${config.scanStep} px", style = MaterialTheme.typography.labelLarge)
                                        }
                                        Slider(
                                            value = config.scanStep.toFloat(),
                                            onValueChange = { v ->
                                                config = config.copy(scanStep = v.roundToInt().coerceIn(1, 25))
                                            },
                                            valueRange = 1f..25f,
                                            enabled = !isRunning
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Margens Ignoradas (Ignorable Pixels)", style = MaterialTheme.typography.bodyMedium)
                                            Text("${config.ignorablePixels} px", style = MaterialTheme.typography.labelLarge)
                                        }
                                        Slider(
                                            value = config.ignorablePixels.toFloat(),
                                            onValueChange = { v ->
                                                config = config.copy(ignorablePixels = v.roundToInt().coerceIn(0, 40))
                                            },
                                            valueRange = 0f..40f,
                                            enabled = !isRunning
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Salvar também na Galeria",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = "Pictures/PanelPull/…_SmartStitch",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = config.saveToGallery,
                                                onCheckedChange = { config = config.copy(saveToGallery = it) },
                                                enabled = !isRunning
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = { onRunStitch(config) },
                                    enabled = !isRunning,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("smartstitch_start_button")
                                ) {
                                    Icon(Icons.Default.ViewAgenda, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isRunning) "Processando SmartStitch…" else "Juntar Páginas (SmartStitch)",
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Stitched Strip Results List
                if (stitchState is SmartStitchUiState.Success) {
                    val stitchedPages = stitchState.chapterWithPages.pages.sortedBy { it.pageIndex }
                    items(stitchedPages, key = { "strip_${it.id}_${it.pageIndex}" }) { stripPage ->
                        val file = stripPage.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                        val isSavedIndividually = stripPage.id in downloadedStripIds
                        val stripNumLabel = (stripPage.pageIndex + 1).toString().padStart(3, '0')
                        val stripExt = file?.extension?.takeIf { it.isNotBlank() } ?: "jpg"
                        val suggestedStripName = "${stitchState.seriesTitle.replace(Regex("[^A-Za-z0-9._-]"), "_")}_SmartStitch_$stripNumLabel.$stripExt"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenStitchedReader(stitchState.chapterWithPages.chapter.id, stripPage.pageIndex)
                                    }
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 64.dp, height = 92.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    ) {
                                        AsyncImage(
                                            model = ComicImageLoader.buildImageRequest(
                                                context = context,
                                                localFile = file,
                                                imageUrl = stripPage.imageUrl,
                                                refererUrl = ""
                                            ),
                                            imageLoader = imageLoader,
                                            contentDescription = "Tira SmartStitch ${stripPage.pageIndex + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Tira #$stripNumLabel",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (isSavedIndividually) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Baixada",
                                                    tint = EmeraldSuccess,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${stripPage.width} × ${stripPage.height} px",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (stripPage.fileSizeBytes > 0L) {
                                            val kb = stripPage.fileSizeBytes / 1024L
                                            Text(
                                                text = if (kb >= 1024) {
                                                    String.format(Locale.US, "%.1f MB", kb / 1024f)
                                                } else {
                                                    "$kb KB"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        Text(
                                            text = "Toque na tira para abrir no leitor",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                downloadedStripIds = downloadedStripIds + stripPage.id
                                                onDownloadStitchedPage(
                                                    stripPage,
                                                    stitchState.chapterWithPages.chapter,
                                                    stitchState.seriesTitle
                                                )
                                            } else {
                                                pendingSingleStrip = stripPage to "Tira #$stripNumLabel"
                                                singleStripSaveLauncher.launch(suggestedStripName)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("smartstitch_download_strip_${stripPage.pageIndex}")
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (isSavedIndividually) "Baixada" else "Baixar",
                                            maxLines = 1
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            pendingSingleStrip = stripPage to "Tira #$stripNumLabel"
                                            singleStripSaveLauncher.launch(suggestedStripName)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("smartstitch_save_as_strip_${stripPage.pageIndex}")
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = "Salvar tira ${stripPage.pageIndex + 1} em pasta"
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = { onCopyStitchedPage(stripPage) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copiar tira ${stripPage.pageIndex + 1}"
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = { onShareStitchedPage(stripPage) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Compartilhar tira ${stripPage.pageIndex + 1}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun StitchProgressCard(
    progress: StitchProgress,
    onCancel: () -> Unit
) {
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
                    text = progress.stageMessage,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCancel) {
                    Text("Cancelar")
                }
            }
            if (progress.totalSteps > 0) {
                val fraction = (progress.currentStep.toFloat() / progress.totalSteps.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
