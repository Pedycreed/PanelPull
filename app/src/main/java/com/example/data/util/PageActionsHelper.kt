package com.example.data.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.extractor.ComicExtractorEngine
import com.example.data.local.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class PageActionsHelper(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    suspend fun copyPageImageToClipboard(page: PageEntity): Boolean = withContext(Dispatchers.IO) {
        val file = ensureLocalFileForAction(page) ?: return@withContext false
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
        true
    }

    suspend fun copyBitmapToClipboard(bitmap: Bitmap, label: String = "PanelPull Arte"): Boolean =
        withContext(Dispatchers.IO) {
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
        }

    fun copyTextToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    suspend fun sharePageImage(page: PageEntity) {
        val file = ensureLocalFileForAction(page) ?: return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = page.mimeType.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar página ${page.pageIndex + 1}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun loadBitmapForAi(page: PageEntity): Bitmap? = withContext(Dispatchers.IO) {
        val local = page.localFilePath?.let { File(it) }?.takeIf { it.exists() }
        if (local != null) {
            return@withContext decodeScaledBitmap(local.readBytes())
        }
        val requestBuilder = Request.Builder()
            .url(page.imageUrl)
            .header("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
            .header("Referer", page.refererUrl)
            .header("Origin", com.example.data.extractor.WebSessionCookieStore.originOf(page.refererUrl))
        com.example.data.extractor.WebSessionCookieStore.getCookieForUrl(page.imageUrl)?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }
        val bytes = okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        } ?: return@withContext null
        decodeScaledBitmap(bytes)
    }

    suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        decodeScaledBitmap(bytes)
    }

    private suspend fun ensureLocalFileForAction(page: PageEntity): File? {
        val local = page.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        if (local != null) return local

        val cacheDir = File(context.cacheDir, "clipboard_pages").apply { mkdirs() }
        val ext = when {
            page.imageUrl.contains(".png", ignoreCase = true) -> "png"
            page.imageUrl.contains(".webp", ignoreCase = true) -> "webp"
            page.imageUrl.contains(".avif", ignoreCase = true) -> "avif"
            else -> "jpg"
        }
        val target = File(cacheDir, "page_${page.id}_${page.pageIndex + 1}.$ext")
        val requestBuilder = Request.Builder()
            .url(page.imageUrl)
            .header("User-Agent", ComicExtractorEngine.BROWSER_USER_AGENT)
            .header("Referer", page.refererUrl)
            .header("Origin", com.example.data.extractor.WebSessionCookieStore.originOf(page.refererUrl))
        com.example.data.extractor.WebSessionCookieStore.getCookieForUrl(page.imageUrl)?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }
        return okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            target.writeBytes(bytes)
            target
        }
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }
}
