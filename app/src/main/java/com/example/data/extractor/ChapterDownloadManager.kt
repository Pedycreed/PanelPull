package com.example.data.extractor

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.local.ChapterEntity
import com.example.data.local.LibraryRepository
import com.example.data.local.PageEntity
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DownloadProgress(
    val chapterId: Long,
    val chapterLabel: String,
    val completedPages: Int,
    val totalPages: Int,
    val isFinished: Boolean = false
)

class ChapterDownloadManager(
    private val context: Context,
    private val repository: LibraryRepository,
    private val okHttpClient: OkHttpClient
) {
    suspend fun downloadChapterOrganized(
        chapterId: Long,
        seriesTitle: String,
        saveToGalleryPictures: Boolean,
        minWidthPx: Int,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadStatus = withContext(Dispatchers.IO) {
        val chapter = repository.getChapterById(chapterId) ?: return@withContext DownloadStatus.FAILED
        val pages = repository.getPagesForChapter(chapterId)
        if (pages.isEmpty()) return@withContext DownloadStatus.FAILED

        val chapterDir = File(chapter.folderPath).apply { mkdirs() }
        val seriesFolder = LibraryRepository.sanitizeFileSegment(seriesTitle)
        val chapterFolder = LibraryRepository.buildChapterFolderName(chapter.chapterNumber, chapter.chapterLabel)

        repository.updateChapterProgress(chapterId, 0, DownloadStatus.DOWNLOADING)

        var successCount = 0
        pages.forEachIndexed { index, page ->
            val progress = DownloadProgress(
                chapterId = chapterId,
                chapterLabel = chapter.chapterLabel,
                completedPages = index,
                totalPages = pages.size
            )
            onProgress(progress)

            try {
                val downloaded = downloadSinglePageInternal(
                    page = page,
                    chapterDir = chapterDir,
                    seriesFolder = seriesFolder,
                    chapterFolder = chapterFolder,
                    saveToGalleryPictures = saveToGalleryPictures,
                    minWidthPx = minWidthPx
                )
                if (downloaded) {
                    successCount++
                }
            } catch (e: CancellationException) {
                repository.updateChapterProgress(chapterId, successCount, DownloadStatus.PARTIAL)
                throw e
            } catch (_: Exception) {
                // Keep trying remaining pages so one broken mirror doesn't abort the chapter.
            }

            repository.updateChapterProgress(chapterId, successCount, DownloadStatus.DOWNLOADING)
        }

        val finalStatus = when {
            successCount == pages.size -> DownloadStatus.COMPLETED
            successCount > 0 -> DownloadStatus.PARTIAL
            else -> DownloadStatus.FAILED
        }
        repository.updateChapterProgress(chapterId, successCount, finalStatus)
        onProgress(
            DownloadProgress(
                chapterId = chapterId,
                chapterLabel = chapter.chapterLabel,
                completedPages = successCount,
                totalPages = pages.size,
                isFinished = true
            )
        )
        finalStatus
    }

    data class SinglePageDownloadOutcome(
        val file: File,
        val savedToGallery: Boolean,
        val galleryDisplayPath: String?
    )

    suspend fun downloadSinglePage(
        page: PageEntity,
        chapter: ChapterEntity,
        seriesTitle: String,
        saveToGalleryPictures: Boolean
    ): SinglePageDownloadOutcome? = withContext(Dispatchers.IO) {
        val seriesFolder = LibraryRepository.sanitizeFileSegment(seriesTitle)
        val rawChapterFolder = LibraryRepository.buildChapterFolderName(chapter.chapterNumber, chapter.chapterLabel)
        val isSmartStitch = chapter.chapterLabel.contains("SmartStitch", ignoreCase = true) ||
            chapter.sourceUrl.endsWith("#smartstitch") ||
            chapter.folderPath.contains("_SmartStitch", ignoreCase = true)
        val chapterFolder = if (isSmartStitch && !rawChapterFolder.endsWith("_SmartStitch")) {
            "${rawChapterFolder}_SmartStitch"
        } else {
            rawChapterFolder
        }
        val galleryRelPath = "${Environment.DIRECTORY_PICTURES}/PanelPull/$seriesFolder/$chapterFolder"

        val existing = page.localFilePath?.let { File(it) }
        if (existing != null && existing.exists() && existing.length() > 0L) {
            var mirrored = false
            if ((saveToGalleryPictures || isSmartStitch) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bytes = existing.readBytes()
                val (ext, mime) = ComicImageLoader.detectFormatFromBytes(bytes, existing.name)
                val displayName = if (isSmartStitch) {
                    "SmartStitch_${(page.pageIndex + 1).toString().padStart(3, '0')}.$ext"
                } else {
                    existing.name
                }
                mirrored = mirrorToPicturesMediaStore(
                    bytes = bytes,
                    displayName = displayName,
                    mimeType = mime,
                    relativePath = galleryRelPath
                )
            }
            return@withContext SinglePageDownloadOutcome(
                file = existing,
                savedToGallery = mirrored,
                galleryDisplayPath = if (mirrored) galleryRelPath else null
            )
        }

        val chapterDir = File(chapter.folderPath).apply { mkdirs() }
        downloadSinglePageInternal(
            page = page,
            chapterDir = chapterDir,
            seriesFolder = seriesFolder,
            chapterFolder = chapterFolder,
            saveToGalleryPictures = saveToGalleryPictures,
            minWidthPx = 0
        )
        val savedFile = repository.getPagesForChapter(chapter.id)
            .firstOrNull { it.id == page.id }
            ?.localFilePath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?: return@withContext null

        SinglePageDownloadOutcome(
            file = savedFile,
            savedToGallery = saveToGalleryPictures && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            galleryDisplayPath = if (saveToGalleryPictures && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) galleryRelPath else null
        )
    }

    suspend fun exportSinglePageToUri(
        page: PageEntity,
        outputUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val localFile = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        val bytes = if (localFile != null) {
            localFile.readBytes()
        } else {
            ComicImageLoader.fetchImageBytes(context, page.imageUrl, page.refererUrl)?.bytes
        } ?: return@withContext false

        if (bytes.isEmpty() || ComicImageLoader.looksLikeHtmlNotImage(bytes)) return@withContext false
        val out = context.contentResolver.openOutputStream(outputUri) ?: return@withContext false
        out.use { it.write(bytes) }
        true
    }

    private suspend fun downloadSinglePageInternal(
        page: PageEntity,
        chapterDir: File,
        seriesFolder: String,
        chapterFolder: String,
        saveToGalleryPictures: Boolean,
        minWidthPx: Int
    ): Boolean {
        val fetched = ComicImageLoader.fetchImageBytes(
            context = context,
            imageUrl = page.imageUrl,
            refererUrl = page.refererUrl
        ) ?: return false

        val bytes = fetched.bytes
        if (bytes.size < 1024 || ComicImageLoader.looksLikeHtmlNotImage(bytes)) return false

        if (!fetched.fromCache) {
            delay(120L)
        }

        val contentType = fetched.contentType.orEmpty().lowercase()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val (detectedExt, detectedMime) = ComicImageLoader.detectFormatFromBytes(bytes, page.imageUrl)
        val isAvif = detectedExt == "avif" || contentType.contains("avif") || page.imageUrl.contains(".avif", ignoreCase = true)
        if ((options.outWidth <= 0 || options.outHeight <= 0) && !isAvif) return false
        if (minWidthPx > 0 && options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth < minWidthPx && options.outHeight < minWidthPx
        ) {
            return false
        }

        val (ext, mimeType) = if (detectedExt.isNotBlank()) {
            detectedExt to detectedMime
        } else {
            resolveExtensionAndMime(page.imageUrl, contentType, options.outMimeType)
        }
        val fileName = "${(page.pageIndex + 1).toString().padStart(3, '0')}.$ext"
        val targetFile = File(chapterDir, fileName)
        targetFile.writeBytes(bytes)

        if (saveToGalleryPictures && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mirrorToPicturesMediaStore(
                bytes = bytes,
                displayName = fileName,
                mimeType = mimeType,
                relativePath = "${Environment.DIRECTORY_PICTURES}/PanelPull/$seriesFolder/$chapterFolder"
            )
        }

        repository.updatePageDownload(
            pageId = page.id,
            path = targetFile.absolutePath,
            mimeType = mimeType,
            width = options.outWidth,
            height = options.outHeight,
            bytes = targetFile.length(),
            downloaded = true
        )
        return true
    }

    private fun mirrorToPicturesMediaStore(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    suspend fun exportChapterToCbzUri(
        pages: List<PageEntity>,
        outputUri: Uri
    ): Int = withContext(Dispatchers.IO) {
        var packed = 0
        val outStream = context.contentResolver.openOutputStream(outputUri) ?: return@withContext 0
        ZipOutputStream(BufferedOutputStream(outStream)).use { zip ->
            pages.sortedBy { it.pageIndex }.forEach { page ->
                val localFile = page.localFilePath?.let { File(it) }?.takeIf { it.exists() }
                if (localFile != null) {
                    zip.putNextEntry(ZipEntry(localFile.name))
                    FileInputStream(localFile).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                    packed++
                }
            }
        }
        packed
    }

    private fun resolveExtensionAndMime(
        url: String,
        headerMime: String,
        decodedMime: String?
    ): Pair<String, String> {
        val mime = decodedMime?.lowercase().orEmpty().ifBlank { headerMime }
        return when {
            mime.contains("png") || url.contains(".png", ignoreCase = true) -> "png" to "image/png"
            mime.contains("webp") || url.contains(".webp", ignoreCase = true) -> "webp" to "image/webp"
            mime.contains("avif") || url.contains(".avif", ignoreCase = true) -> "avif" to "image/avif"
            else -> "jpg" to "image/jpeg"
        }
    }
}
