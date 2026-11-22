package com.example.data.local

import com.example.data.model.DownloadStatus
import com.example.data.model.ExtractionResult
import kotlinx.coroutines.flow.Flow
import java.io.File

class LibraryRepository(
    private val dao: ComicDao,
    private val baseChaptersDir: File
) {
    val libraryFlow: Flow<List<SeriesWithChapters>> = dao.observeLibrary()

    fun observeChapter(chapterId: Long): Flow<ChapterWithPages?> =
        dao.observeChapterWithPages(chapterId)

    suspend fun saveExtractionToLibrary(result: ExtractionResult): ChapterWithPages {
        val seriesSlug = sanitizeFileSegment(result.seriesTitle).ifBlank { "obra" }
        val chapterSlug = buildChapterFolderName(result.chapterNumber, result.chapterLabel)

        val seriesDir = File(baseChaptersDir, seriesSlug).apply { mkdirs() }
        val chapterDir = File(seriesDir, chapterSlug).apply { mkdirs() }

        val existingSeries = dao.getSeriesBySlug(seriesSlug)
        val seriesId = if (existingSeries != null) {
            dao.updateSeries(
                existingSeries.copy(
                    title = result.seriesTitle,
                    comicFormat = result.comicFormat.name,
                    coverPathOrUrl = existingSeries.coverPathOrUrl.ifBlank {
                        result.pages.firstOrNull()?.imageUrl.orEmpty()
                    },
                    updatedAt = System.currentTimeMillis()
                )
            )
            existingSeries.id
        } else {
            dao.insertSeries(
                SeriesEntity(
                    title = result.seriesTitle,
                    slug = seriesSlug,
                    sourceHost = result.siteHost,
                    comicFormat = result.comicFormat.name,
                    coverPathOrUrl = result.pages.firstOrNull()?.imageUrl.orEmpty(),
                    folderPath = seriesDir.absolutePath
                )
            )
        }

        val existingChapter = dao.getChapterBySourceUrl(result.sourceUrl)
        val chapterId = if (existingChapter != null) {
            dao.deletePagesForChapter(existingChapter.id)
            dao.updateChapter(
                existingChapter.copy(
                    seriesId = seriesId,
                    chapterNumber = result.chapterNumber,
                    chapterLabel = result.chapterLabel,
                    folderPath = chapterDir.absolutePath,
                    pageCount = result.pages.size,
                    downloadedCount = 0,
                    status = DownloadStatus.EXTRACTED.name
                )
            )
            existingChapter.id
        } else {
            dao.insertChapter(
                ChapterEntity(
                    seriesId = seriesId,
                    chapterNumber = result.chapterNumber,
                    chapterLabel = result.chapterLabel,
                    sourceUrl = result.sourceUrl,
                    folderPath = chapterDir.absolutePath,
                    pageCount = result.pages.size,
                    downloadedCount = 0,
                    status = DownloadStatus.EXTRACTED.name
                )
            )
        }

        val pageEntities = result.pages.mapIndexed { idx, candidate ->
            PageEntity(
                chapterId = chapterId,
                pageIndex = idx,
                imageUrl = candidate.imageUrl,
                refererUrl = candidate.refererUrl,
                width = candidate.widthHint,
                height = candidate.heightHint
            )
        }
        dao.insertPages(pageEntities)

        val storedChapter = dao.getChapterById(chapterId)!!
        val storedPages = dao.getPagesForChapter(chapterId)
        return ChapterWithPages(storedChapter, storedPages)
    }

    suspend fun getPagesForChapter(chapterId: Long): List<PageEntity> =
        dao.getPagesForChapter(chapterId)

    suspend fun getChapterById(chapterId: Long): ChapterEntity? =
        dao.getChapterById(chapterId)

    suspend fun updatePageDownload(
        pageId: Long,
        path: String?,
        mimeType: String,
        width: Int,
        height: Int,
        bytes: Long,
        downloaded: Boolean
    ) {
        dao.updatePageDownload(pageId, path, mimeType, width, height, bytes, downloaded)
    }

    suspend fun updateChapterProgress(chapterId: Long, downloadedCount: Int, status: DownloadStatus) {
        dao.updateChapterProgress(chapterId, downloadedCount, status.name)
    }

    suspend fun markPageCopied(pageId: Long) {
        dao.markPageCopied(pageId)
    }

    suspend fun deleteChapter(chapter: ChapterEntity) {
        File(chapter.folderPath).deleteRecursively()
        dao.deleteChapterById(chapter.id)
    }

    suspend fun deleteSeries(series: SeriesEntity) {
        File(series.folderPath).deleteRecursively()
        dao.deleteSeriesById(series.id)
    }

    companion object {
        fun sanitizeFileSegment(raw: String): String {
            return raw.trim()
                .replace(Regex("[^a-zA-Z0-9\\s._-]"), "_")
                .replace(Regex("\\s+"), "_")
                .trim('_')
                .take(64)
                .ifBlank { "Sem_Titulo" }
        }

        fun buildChapterFolderName(chapterNumber: String, chapterLabel: String): String {
            val cleanNum = chapterNumber.trim().replace(Regex("[^0-9.]"), "")
            return if (cleanNum.isNotBlank()) {
                val padded = cleanNum.split(".").let { parts ->
                    val major = parts[0].padStart(3, '0')
                    if (parts.size > 1) "${major}.${parts[1]}" else major
                }
                "Capitulo_$padded"
            } else {
                sanitizeFileSegment(chapterLabel).ifBlank { "Capitulo" }
            }
        }
    }
}
