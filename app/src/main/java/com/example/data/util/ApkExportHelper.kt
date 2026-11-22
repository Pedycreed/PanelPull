package com.example.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class InstalledApkInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val packageName: String,
    val suggestedFileName: String,
    val sizeBytes: Long,
    val isAvailable: Boolean
) {
    val formattedSize: String
        get() {
            if (sizeBytes <= 0L) return "Indisponível"
            val mb = sizeBytes / (1024.0 * 1024.0)
            return String.format(Locale.getDefault(), "%.1f MB", mb)
        }
}

class ApkExportHelper(private val context: Context) {

    fun getInstalledApkInfo(): InstalledApkInfo {
        val sourcePath = context.applicationInfo?.sourceDir
        val apkFile = sourcePath?.let { File(it) }?.takeIf { it.exists() && it.isFile }
        val versionName = BuildConfig.VERSION_NAME
        val fileName = "PanelPull-v${versionName}.apk"
        return InstalledApkInfo(
            appName = "PanelPull",
            versionName = versionName,
            versionCode = BuildConfig.VERSION_CODE,
            packageName = context.packageName,
            suggestedFileName = fileName,
            sizeBytes = apkFile?.length() ?: 0L,
            isAvailable = apkFile != null
        )
    }

    suspend fun exportApkToUri(outputUri: Uri): Long = withContext(Dispatchers.IO) {
        val sourcePath = context.applicationInfo?.sourceDir
            ?: throw IllegalStateException("Arquivo APK base não encontrado no dispositivo.")
        val apkFile = File(sourcePath)
        if (!apkFile.exists() || !apkFile.canRead()) {
            throw IllegalStateException("Não foi possível ler o pacote APK instalado.")
        }
        val outStream = context.contentResolver.openOutputStream(outputUri)
            ?: throw IllegalStateException("Não foi possível abrir o destino escolhido.")
        var copied = 0L
        outStream.use { output ->
            FileInputStream(apkFile).use { input ->
                copied = input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        copied
    }

    suspend fun shareInstalledApk(): Boolean = withContext(Dispatchers.IO) {
        val sourcePath = context.applicationInfo?.sourceDir ?: return@withContext false
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourceFile.canRead()) return@withContext false

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val targetFile = File(exportsDir, "PanelPull-v${BuildConfig.VERSION_NAME}.apk")
        sourceFile.copyTo(targetFile, overwrite = true)

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            putExtra(Intent.EXTRA_SUBJECT, "PanelPull v${BuildConfig.VERSION_NAME} (Android APK)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "Enviar instalador APK do PanelPull").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }
}
