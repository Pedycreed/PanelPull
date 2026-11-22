package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.local.ChapterEntity
import com.example.data.local.ChapterWithPages
import com.example.data.local.PageEntity
import com.example.data.local.SeriesEntity
import com.example.data.local.SeriesWithChapters
import com.example.ui.theme.EmeraldSuccess

@Composable
fun LibraryScreen(
    library: List<SeriesWithChapters>,
    onOpenReader: (chapterId: Long) -> Unit,
    onDownloadChapter: (chapterId: Long, seriesTitle: String) -> Unit,
    onExportCbz: (pages: List<PageEntity>, outputUri: Uri, label: String) -> Unit,
    onDeleteChapter: (ChapterEntity) -> Unit,
    onDeleteSeries: (SeriesEntity) -> Unit,
    onGoToExtractor: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingExportChapter by remember { mutableStateOf<Pair<String, List<PageEntity>>?>(null) }

    val createCbzLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-cbz")
    ) { uri ->
        val pending = pendingExportChapter
        if (uri != null && pending != null) {
            onExportCbz(pending.second, uri, pending.first)
        }
        pendingExportChapter = null
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Biblioteca Organizada",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Capítulos agrupados automaticamente por Obra / Capítulo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (library.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_empty_pages_1795118341482),
                                contentDescription = "Biblioteca vazia",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .aspectRatio(4f / 3f)
                                    .clip(MaterialTheme.shapes.medium)
                            )
                            Text(
                                text = stringResource(R.string.empty_library_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(R.string.empty_library_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onGoToExtractor,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("empty_library_extract_cta")
                            ) {
                                Text("Extrair Primeiro Capítulo")
                            }
                        }
                    }
                }
            } else {
                items(library, key = { it.series.id }) { seriesItem ->
                    SeriesArchiveCard(
                        seriesWithChapters = seriesItem,
                        onOpenReader = onOpenReader,
                        onDownloadChapter = { chId ->
                            onDownloadChapter(chId, seriesItem.series.title)
                        },
                        onRequestCbzExport = { chLabel, pages ->
                            val cleanName = "${seriesItem.series.slug}_${chLabel.replace(" ", "_")}.cbz"
                            pendingExportChapter = "${seriesItem.series.title} • $chLabel" to pages
                            createCbzLauncher.launch(cleanName)
                        },
                        onDeleteChapter = onDeleteChapter,
                        onDeleteSeries = { onDeleteSeries(seriesItem.series) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesArchiveCard(
    seriesWithChapters: SeriesWithChapters,
    onOpenReader: (Long) -> Unit,
    onDownloadChapter: (Long) -> Unit,
    onRequestCbzExport: (chapterLabel: String, pages: List<PageEntity>) -> Unit,
    onDeleteChapter: (ChapterEntity) -> Unit,
    onDeleteSeries: () -> Unit
) {
    val context = LocalContext.current
    val series = seriesWithChapters.series
    val chapters = seriesWithChapters.chapters.sortedByDescending { it.chapter.createdAt }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (series.coverPathOrUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(series.coverPathOrUrl)
                            .addHeader("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Capa de ${series.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 62.dp, height = 84.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${series.comicFormat} • ${series.sourceHost}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = series.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "PanelPull/${series.slug}/ • ${chapters.size} cap.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDeleteSeries,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remover obra ${series.title}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            chapters.forEach { chWithPages ->
                ChapterArchiveRow(
                    chapterWithPages = chWithPages,
                    onOpenReader = { onOpenReader(chWithPages.chapter.id) },
                    onDownload = { onDownloadChapter(chWithPages.chapter.id) },
                    onExportCbz = {
                        onRequestCbzExport(chWithPages.chapter.chapterLabel, chWithPages.pages)
                    },
                    onDelete = { onDeleteChapter(chWithPages.chapter) }
                )
            }
        }
    }
}

@Composable
private fun ChapterArchiveRow(
    chapterWithPages: ChapterWithPages,
    onOpenReader: () -> Unit,
    onDownload: () -> Unit,
    onExportCbz: () -> Unit,
    onDelete: () -> Unit
) {
    val ch = chapterWithPages.chapter
    val isComplete = ch.downloadedCount >= ch.pageCount && ch.pageCount > 0

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ch.chapterLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${ch.downloadedCount}/${ch.pageCount} páginas salvas • ${if (isComplete) "Organizado" else ch.status}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isComplete) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Excluir ${ch.chapterLabel}"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenReader,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("library_open_chapter_${ch.id}")
                ) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ler / Copiar", maxLines = 1)
                }

                if (!isComplete) {
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Baixar", maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = onExportCbz,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export .CBZ", maxLines = 1)
                    }
                }
            }
        }
    }
}
