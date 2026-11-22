package com.example.data.stitch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.extractor.ComicImageLoader
import com.example.data.local.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val STITCH_TAG = "SmartStitchEngine"
private const val MAX_SINGLE_STRIP_HEIGHT = 20_000

enum class WidthEnforcementMode(val label: String, val description: String) {
    AUTOMATIC("Menor Largura", "Uniformiza todas as páginas pela menor largura"),
    CUSTOM("Personalizada", "Redimensiona todas para uma largura fixa (px)"),
    NONE("Original", "Mantém largura nativa sem redimensionar")
}

enum class StitchDetectorMode(val label: String, val description: String) {
    SMART_PIXEL("SmartStitch", "Detecta espaços vazios para não cortar balões/arte"),
    DIRECT("Corte Fixo", "Fatia mecanicamente na altura definida"),
    SINGLE_STRIP("Tira Única", "Une todas as páginas em imagem contínua")
}

enum class StitchOutputFormat(val label: String, val ext: String, val mimeType: String) {
    JPEG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WEBP", "webp", "image/webp")
}

data class SmartStitchConfig(
    val detectorMode: StitchDetectorMode = StitchDetectorMode.SMART_PIXEL,
    val splitHeight: Int = 5000,
    val sensitivity: Int = 90,
    val ignorablePixels: Int = 5,
    val scanStep: Int = 5,
    val widthEnforcement: WidthEnforcementMode = WidthEnforcementMode.AUTOMATIC,
    val customWidthPx: Int = 800,
    val outputFormat: StitchOutputFormat = StitchOutputFormat.JPEG,
    val compressionQuality: Int = 92,
    val saveToGallery: Boolean = true
)

data class StitchProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val stageMessage: String
)

data class StitchedOutputFile(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String
)

data class SmartStitchResult(
    val inputPageCount: Int,
    val outputFiles: List<StitchedOutputFile>,
    val outputDir: File,
    val targetWidth: Int
)

private data class VirtualPageSegment(
    val file: File,
    val originalWidth: Int,
    val originalHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val startY: Int,
    val endY: Int
)

/**
 * Native Android/Kotlin implementation of MechTechnology/SmartStitch:
 * - Joins sequential manhwa/manga/webtoon pages vertically with configurable Width Enforcement.
 * - Uses PixelComparisonDetector (sensitivity 0..100, ignorable margins, scan step) to locate
 *   clean blank/uniform gutters so slices never cut through dialogue bubbles or panel artwork.
 * - Uses a memory-safe streaming sliding-window bitmap cache (max 2 decoded pages in RAM) so
 *   even 40+ page webtoon chapters process smoothly on Android phones without OOM.
 */
class SmartStitchEngine(
    private val context: Context
) {

    /**
     * Resolves remote/cached chapter pages to local files first, then runs SmartStitch.
     */
    suspend fun stitchChapterPages(
        pages: List<PageEntity>,
        outputDir: File,
        galleryRelativePath: String?,
        config: SmartStitchConfig,
        onProgress: (StitchProgress) -> Unit
    ): SmartStitchResult = withContext(Dispatchers.IO) {
        val sortedPages = pages.sortedBy { it.pageIndex }
        require(sortedPages.isNotEmpty()) { "Nenhuma página disponível para juntar." }

        val tempInputDir = File(context.cacheDir, "smartstitch_inputs/${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            val localInputFiles = mutableListOf<File>()
            sortedPages.forEachIndexed { idx, page ->
                coroutineContext.ensureActive()
                onProgress(
                    StitchProgress(
                        currentStep = idx + 1,
                        totalSteps = sortedPages.size,
                        stageMessage = "Preparando página ${idx + 1} de ${sortedPages.size}…"
                    )
                )

                val existing = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 100L }
                if (existing != null) {
                    localInputFiles.add(existing)
                } else {
                    val fetched = ComicImageLoader.fetchImageBytes(context, page.imageUrl, page.refererUrl)
                    if (fetched != null && !ComicImageLoader.looksLikeHtmlNotImage(fetched.bytes)) {
                        val (ext, _) = ComicImageLoader.detectFormatFromBytes(fetched.bytes, page.imageUrl)
                        val tmp = File(tempInputDir, "${(idx + 1).toString().padStart(4, '0')}.$ext")
                        tmp.writeBytes(fetched.bytes)
                        localInputFiles.add(tmp)
                    }
                }
            }

            if (localInputFiles.isEmpty()) {
                throw IllegalStateException("Não foi possível carregar as imagens das páginas para o SmartStitch.")
            }

            stitchFiles(
                inputFiles = localInputFiles,
                outputDir = outputDir,
                galleryRelativePath = galleryRelativePath,
                config = config,
                onProgress = onProgress
            )
        } finally {
            tempInputDir.deleteRecursively()
        }
    }

    /**
     * Core SmartStitch pipeline over an ordered list of local image files.
     */
    suspend fun stitchFiles(
        inputFiles: List<File>,
        outputDir: File,
        galleryRelativePath: String?,
        config: SmartStitchConfig,
        onProgress: (StitchProgress) -> Unit
    ): SmartStitchResult = withContext(Dispatchers.IO) {
        require(inputFiles.isNotEmpty()) { "Selecione pelo menos 1 imagem para juntar." }

        // 1. Inspect dimensions of all valid input images
        val measured = mutableListOf<Triple<File, Int, Int>>()
        for (file in inputFiles) {
            coroutineContext.ensureActive()
            val dims = readImageDimensions(file)
            if (dims != null && dims.first > 0 && dims.second > 0) {
                measured.add(Triple(file, dims.first, dims.second))
            }
        }
        if (measured.isEmpty()) {
            throw IllegalStateException("Nenhuma imagem válida encontrada para juntar.")
        }

        // 2. Compute target canvas width according to SmartStitch Width Enforcement mode
        val targetWidth = when (config.widthEnforcement) {
            WidthEnforcementMode.AUTOMATIC -> measured.minOf { it.second }.coerceIn(200, 2400)
            WidthEnforcementMode.CUSTOM -> config.customWidthPx.coerceIn(200, 2400)
            WidthEnforcementMode.NONE -> measured.maxOf { it.second }.coerceIn(200, 2400)
        }

        // 3. Build virtual vertical strip coordinate map
        var totalHeight = 0
        val segments = measured.map { (file, origW, origH) ->
            val (scaledW, scaledH) = when (config.widthEnforcement) {
                WidthEnforcementMode.NONE -> {
                    if (origW > targetWidth) {
                        val ratio = targetWidth.toFloat() / origW.toFloat()
                        targetWidth to (origH * ratio).roundToInt().coerceAtLeast(1)
                    } else {
                        origW to origH
                    }
                }
                WidthEnforcementMode.AUTOMATIC, WidthEnforcementMode.CUSTOM -> {
                    val ratio = targetWidth.toFloat() / origW.toFloat()
                    targetWidth to (origH * ratio).roundToInt().coerceAtLeast(1)
                }
            }
            val seg = VirtualPageSegment(
                file = file,
                originalWidth = origW,
                originalHeight = origH,
                scaledWidth = scaledW,
                scaledHeight = scaledH,
                startY = totalHeight,
                endY = totalHeight + scaledH
            )
            totalHeight += scaledH
            seg
        }

        // Clean output directory for fresh stitched strips
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach { it.delete() }
        } else {
            outputDir.mkdirs()
        }

        // Sliding 2-entry bitmap cache keeps peak RAM tiny even on 50-page webtoons
        val cache = LinkedHashMap<Int, Bitmap>(3, 0.75f, true)
        fun getSegmentBitmap(segIndex: Int): Bitmap? {
            cache[segIndex]?.let { if (!it.isRecycled) return it }
            if (cache.size >= 2) {
                val eldestKey = cache.keys.firstOrNull()
                if (eldestKey != null) {
                    cache.remove(eldestKey)?.recycle()
                }
            }
            val seg = segments[segIndex]
            val bmp = decodeScaledBitmap(seg.file, seg.scaledWidth, seg.scaledHeight) ?: return null
            cache[segIndex] = bmp
            return bmp
        }

        val rowBuffer = IntArray(targetWidth)
        fun readVirtualRow(virtualY: Int): IntArray? {
            if (virtualY < 0 || virtualY >= totalHeight) return null
            val segIdx = findSegmentIndexForY(segments, virtualY) ?: return null
            val seg = segments[segIdx]
            val bmp = getSegmentBitmap(segIdx) ?: return null
            val localY = (virtualY - seg.startY).coerceIn(0, bmp.height - 1)

            if (bmp.width == targetWidth) {
                bmp.getPixels(rowBuffer, 0, targetWidth, 0, localY, targetWidth, 1)
            } else {
                rowBuffer.fill(Color.WHITE)
                val offsetX = ((targetWidth - bmp.width) / 2).coerceAtLeast(0)
                val copyW = minOf(bmp.width, targetWidth - offsetX)
                bmp.getPixels(rowBuffer, offsetX, targetWidth, 0, localY, copyW, 1)
            }
            return rowBuffer
        }

        val effectiveSplitHeight = when (config.detectorMode) {
            StitchDetectorMode.SINGLE_STRIP -> minOf(totalHeight, MAX_SINGLE_STRIP_HEIGHT)
            StitchDetectorMode.SMART_PIXEL, StitchDetectorMode.DIRECT -> config.splitHeight.coerceIn(1000, MAX_SINGLE_STRIP_HEIGHT)
        }

        val outputFiles = mutableListOf<StitchedOutputFile>()
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            var currentY = 0
            var sliceIndex = 0
            val estimatedSlices = ((totalHeight + effectiveSplitHeight - 1) / effectiveSplitHeight).coerceAtLeast(1)

            while (currentY < totalHeight) {
                coroutineContext.ensureActive()
                sliceIndex++
                onProgress(
                    StitchProgress(
                        currentStep = sliceIndex.coerceAtMost(estimatedSlices),
                        totalSteps = maxOf(estimatedSlices, sliceIndex),
                        stageMessage = "SmartStitch gerando tira #$sliceIndex…"
                    )
                )

                val remaining = totalHeight - currentY
                val cutY = if (remaining <= effectiveSplitHeight) {
                    totalHeight
                } else {
                    when (config.detectorMode) {
                        StitchDetectorMode.DIRECT -> currentY + effectiveSplitHeight
                        StitchDetectorMode.SINGLE_STRIP -> {
                            // If total strip exceeds Android safe max height (20,000px), use smart cut near limit
                            findSmartCutY(
                                startY = currentY,
                                targetHeight = effectiveSplitHeight,
                                totalHeight = totalHeight,
                                sensitivity = config.sensitivity,
                                ignorablePixels = config.ignorablePixels,
                                scanStep = config.scanStep,
                                readRow = ::readVirtualRow
                            )
                        }
                        StitchDetectorMode.SMART_PIXEL -> {
                            findSmartCutY(
                                startY = currentY,
                                targetHeight = effectiveSplitHeight,
                                totalHeight = totalHeight,
                                sensitivity = config.sensitivity,
                                ignorablePixels = config.ignorablePixels,
                                scanStep = config.scanStep,
                                readRow = ::readVirtualRow
                            )
                        }
                    }
                }.coerceIn(currentY + 1, totalHeight)

                val sliceHeight = cutY - currentY
                val sliceBitmap = Bitmap.createBitmap(targetWidth, sliceHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sliceBitmap)
                canvas.drawColor(Color.WHITE)

                // Draw all segments intersecting [currentY, cutY)
                segments.forEachIndexed { segIdx, seg ->
                    if (seg.endY > currentY && seg.startY < cutY) {
                        val bmp = getSegmentBitmap(segIdx)
                        if (bmp != null) {
                            val srcTop = (currentY - seg.startY).coerceAtLeast(0)
                            val srcBottom = (cutY - seg.startY).coerceAtMost(bmp.height)
                            val dstTop = (seg.startY - currentY).coerceAtLeast(0)
                            val dstBottom = dstTop + (srcBottom - srcTop)
                            val dstLeft = ((targetWidth - bmp.width) / 2).coerceAtLeast(0)
                            val srcRect = Rect(0, srcTop, bmp.width, srcBottom)
                            val dstRect = Rect(dstLeft, dstTop, dstLeft + bmp.width, dstBottom)
                            canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
                        }
                    }
                }

                val fileName = "${sliceIndex.toString().padStart(3, '0')}.${config.outputFormat.ext}"
                val outFile = File(outputDir, fileName)
                compressBitmapToFile(
                    bitmap = sliceBitmap,
                    outputFile = outFile,
                    format = config.outputFormat,
                    quality = config.compressionQuality
                )
                sliceBitmap.recycle()

                if (config.saveToGallery && !galleryRelativePath.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mirrorStitchedFileToGallery(
                        file = outFile,
                        displayName = fileName,
                        mimeType = config.outputFormat.mimeType,
                        relativePath = galleryRelativePath
                    )
                }

                outputFiles.add(
                    StitchedOutputFile(
                        file = outFile,
                        width = targetWidth,
                        height = sliceHeight,
                        mimeType = config.outputFormat.mimeType
                    )
                )
                currentY = cutY
            }
        } finally {
            cache.values.forEach { if (!it.isRecycled) it.recycle() }
            cache.clear()
        }

        SmartStitchResult(
            inputPageCount = measured.size,
            outputFiles = outputFiles,
            outputDir = outputDir,
            targetWidth = targetWidth
        )
    }

    private fun findSegmentIndexForY(segments: List<VirtualPageSegment>, y: Int): Int? {
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val seg = segments[mid]
            when {
                y < seg.startY -> high = mid - 1
                y >= seg.endY -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }

    private fun readImageDimensions(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return opts.outWidth to opts.outHeight
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                var w = 0
                var h = 0
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { _, info, _ ->
                    w = info.size.width
                    h = info.size.height
                }.recycle()
                if (w > 0 && h > 0) w to h else null
            }.getOrNull()
        }
        return null
    }

    private fun decodeScaledBitmap(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val rawBitmap = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(targetW, targetH)
                }
            } else {
                null
            } ?: return null

            if (rawBitmap.width == targetW && rawBitmap.height == targetH) {
                rawBitmap
            } else {
                val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
                if (scaled !== rawBitmap) rawBitmap.recycle()
                scaled
            }
        } catch (e: Exception) {
            Log.e(STITCH_TAG, "Failed to decode segment ${file.name}", e)
            null
        }
    }

    private fun compressBitmapToFile(
        bitmap: Bitmap,
        outputFile: File,
        format: StitchOutputFormat,
        quality: Int
    ) {
        val safeQuality = quality.coerceIn(50, 100)
        val compressFormat = when (format) {
            StitchOutputFormat.PNG -> Bitmap.CompressFormat.PNG
            StitchOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            StitchOutputFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
        }
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(compressFormat, safeQuality, out)
        }
    }

    private fun mirrorStitchedFileToGallery(
        file: File,
        displayName: String,
        mimeType: String,
        relativePath: String
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
        }
    }

    companion object {
        /**
         * Authentic MechTechnology/SmartStitch PixelComparisonDetector row check:
         * Determines whether a horizontal row is a uniform gutter safe for slicing
         * (no dialogue bubbles, SFX, or artwork crossing this row).
         */
        fun isRowSafeForCut(
            rowPixels: IntArray,
            ignorablePixels: Int,
            sensitivity: Int
        ): Boolean {
            if (rowPixels.isEmpty()) return true
            val clampedSensitivity = sensitivity.coerceIn(0, 100)
            if (clampedSensitivity == 0) return true

            val threshold = ((100 - clampedSensitivity) * 2.55).roundToInt()
            val margin = ignorablePixels.coerceIn(0, (rowPixels.size / 4).coerceAtLeast(0))
            val startX = margin
            val endX = (rowPixels.size - 1 - margin).coerceAtLeast(startX)

            val basePixel = rowPixels[startX]
            val baseR = Color.red(basePixel)
            val baseG = Color.green(basePixel)
            val baseB = Color.blue(basePixel)

            for (x in (startX + 1)..endX) {
                val px = rowPixels[x]
                if (abs(Color.red(px) - baseR) > threshold ||
                    abs(Color.green(px) - baseG) > threshold ||
                    abs(Color.blue(px) - baseB) > threshold
                ) {
                    return false
                }
            }
            return true
        }

        /**
         * Searches around `startY + targetHeight` using SmartStitch's alternating scan step
         * to pick the nearest bubble-safe horizontal gutter row.
         */
        fun findSmartCutY(
            startY: Int,
            targetHeight: Int,
            totalHeight: Int,
            sensitivity: Int,
            ignorablePixels: Int,
            scanStep: Int,
            readRow: (Int) -> IntArray?
        ): Int {
            val idealY = (startY + targetHeight).coerceAtMost(totalHeight)
            if (idealY >= totalHeight) return totalHeight
            if (sensitivity <= 0) return idealY

            val step = scanStep.coerceIn(1, 50)
            val minCutY = (startY + (targetHeight * 0.35f).roundToInt()).coerceAtLeast(startY + 1)
            val maxCutY = minOf(totalHeight - 1, startY + (targetHeight * 1.15f).roundToInt())

            // First check the exact target row
            readRow(idealY)?.let { row ->
                if (isRowSafeForCut(row, ignorablePixels, sensitivity)) {
                    return idealY
                }
            }

            // Scan outward from idealY (preferring upward safe gutters first to respect targetHeight ceiling)
            val maxDistance = maxOf(idealY - minCutY, maxCutY - idealY)
            var offset = step
            while (offset <= maxDistance) {
                val upY = idealY - offset
                if (upY >= minCutY) {
                    readRow(upY)?.let { row ->
                        if (isRowSafeForCut(row, ignorablePixels, sensitivity)) {
                            return upY
                        }
                    }
                }
                val downY = idealY + offset
                if (downY <= maxCutY) {
                    readRow(downY)?.let { row ->
                        if (isRowSafeForCut(row, ignorablePixels, sensitivity)) {
                            return downY
                        }
                    }
                }
                offset += step
            }

            return idealY
        }
    }
}
