package com.example.data.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.extractor.ComicImageLoader
import com.example.data.local.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

private const val PAGE_ACTIONS_TAG = "PageActionsHelper"

data class CopyPageResult(
    val success: Boolean,
    val errorDetail: String? = null
)

class PageActionsHelper(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val okHttpClient: OkHttpClient
) {
    suspend fun copyPageImageToClipboard(page: PageEntity): CopyPageResult = withContext(Dispatchers.IO) {
        try {
            val file = ensureLocalFileForClipboard(page)
                ?: return@withContext CopyPageResult(
                    success = false,
                    errorDetail = "O servidor não liberou a imagem agora. Tente baixar o capítulo ou abrir no Navegador JS."
                )

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newUri(
                    context.contentResolver,
                    "PanelPull Página ${page.pageIndex + 1}",
                    uri
                )
                clipboard.setPrimaryClip(clip)
            }
            CopyPageResult(success = true)
        } catch (e: Exception) {
            Log.e(PAGE_ACTIONS_TAG, "Failed to copy page ${page.pageIndex + 1} (${page.imageUrl})", e)
            CopyPageResult(
                success = false,
                errorDetail = e.localizedMessage ?: "Erro interno ao preparar imagem."
            )
        }
    }

    suspend fun copyBitmapToClipboard(bitmap: Bitmap, label: String = "PanelPull Arte"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "clipboard_pages").apply { mkdirs() }
                val outFile = File(cacheDir, "studio_${System.currentTimeMillis()}.png")
                FileOutputStream(outFile).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
                }
                true
            } catch (e: Exception) {
                Log.e(PAGE_ACTIONS_TAG, "Failed to copy studio bitmap", e)
                false
            }
        }

    fun copyTextToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    suspend fun sharePageImage(page: PageEntity) {
        try {
            val file = ensureLocalFileForClipboard(page) ?: return
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val (_, mime) = ComicImageLoader.detectFormatFromBytes(file.readBytes(), file.name)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar página ${page.pageIndex + 1}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(PAGE_ACTIONS_TAG, "Failed to share page ${page.pageIndex + 1}", e)
        }
    }

    suspend fun loadBitmapForAi(page: PageEntity): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val local = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val bytes = if (local != null) {
                local.readBytes()
            } else {
                ComicImageLoader.fetchImageBytes(context, page.imageUrl, page.refererUrl)?.bytes
            } ?: return@withContext null

            if (ComicImageLoader.looksLikeHtmlNotImage(bytes)) return@withContext null
            decodeScaledBitmap(bytes)
        } catch (e: Exception) {
            Log.e(PAGE_ACTIONS_TAG, "Failed to load bitmap for AI", e)
            null
        }
    }

    suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            decodeScaledBitmap(bytes)
        } catch (e: Exception) {
            Log.e(PAGE_ACTIONS_TAG, "Failed to load bitmap from uri", e)
            null
        }
    }

    /**
     * Prepares a local PNG/JPEG file inside `cacheDir/clipboard_pages/` (guaranteed to be exposed by
     * FileProvider and universally accepted by Android Clipboard, Gboard, HyperOS, WhatsApp, etc.).
     * Reuses downloaded file OR Coil's DiskCache first before making any network request.
     */
    private suspend fun ensureLocalFileForClipboard(page: PageEntity): File? {
        val cacheDir = File(context.cacheDir, "clipboard_pages").apply { mkdirs() }

        // 1. Read raw bytes from local downloaded file OR shared ComicImageLoader (DiskCache-first + retry)
        val localDownloaded = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 100L }
        val rawBytes = if (localDownloaded != null) {
            localDownloaded.readBytes()
        } else {
            ComicImageLoader.fetchImageBytes(context, page.imageUrl, page.refererUrl)?.bytes
        } ?: return null

        if (ComicImageLoader.looksLikeHtmlNotImage(rawBytes)) {
            Log.w(PAGE_ACTIONS_TAG, "Fetched payload for page ${page.pageIndex + 1} was HTML instead of an image")
            return null
        }

        val (ext, _) = ComicImageLoader.detectFormatFromBytes(rawBytes, page.imageUrl)

        // 2. If image is already PNG or JPEG, write directly to clipboard_pages with matching extension
        if (ext == "png" || ext == "jpg") {
            val target = File(cacheDir, "page_${page.id}_${page.pageIndex + 1}.$ext")
            target.writeBytes(rawBytes)
            return target
        }

        // 3. For WEBP / AVIF / other formats, transcode to PNG so Android keyboards & chat apps paste reliably
        val decoded = decodeScaledBitmap(rawBytes, maxDim = 4096)
        if (decoded != null) {
            val pngTarget = File(cacheDir, "page_${page.id}_${page.pageIndex + 1}.png")
            FileOutputStream(pngTarget).use { out ->
                decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return pngTarget
        }

        // Fallback: save original bytes with detected extension
        val fallbackTarget = File(cacheDir, "page_${page.id}_${page.pageIndex + 1}.$ext")
        fallbackTarget.writeBytes(rawBytes)
        return fallbackTarget
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            if (bmp != null) return bmp
        }

        // Fallback to Android ImageDecoder (API 28+) for AVIF / HEIF / lossless WebP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > maxDim || h > maxDim) {
                        val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        }
        return null
    }
}
