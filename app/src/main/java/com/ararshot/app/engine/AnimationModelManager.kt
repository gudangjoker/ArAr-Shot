package com.ararshot.app.engine

import android.content.Context
import android.util.Log
import com.ararshot.app.model.AnimationStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages manual on-demand download, TFLite integrity validation, and local storage
 * of all 7 non-anime animation engines inside app-private filesDir.
 */
object AnimationModelManager {
    private const val TAG = "ArArAnimModels"
    private const val ROOT_DIR_NAME = "animation_models"

    fun styleDir(context: Context, style: AnimationStyle): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR_NAME), style.id)

    fun modelFile(context: Context, style: AnimationStyle, fileName: String): File =
        File(styleDir(context, style), fileName)

    fun isInstalled(context: Context, style: AnimationStyle): Boolean {
        val dir = styleDir(context, style)
        if (!dir.exists()) return false
        return style.files.all { spec ->
            val f = File(dir, spec.fileName)
            f.exists() && f.length() > 100_000L && hasValidTfliteHeader(f)
        }
    }

    fun getInstalledStyleIds(context: Context): Set<String> =
        AnimationStyle.entries
            .filter { isInstalled(context, it) }
            .map { it.id }
            .toSet()

    fun getTotalInstalledBytes(context: Context): Long {
        val root = File(context.applicationContext.filesDir, ROOT_DIR_NAME)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sumOf { it.length() }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }

    fun deleteStyle(context: Context, style: AnimationStyle): Boolean {
        AnimationEngine.evictStyle(style)
        val dir = styleDir(context, style)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun deleteAllStyles(context: Context): Boolean {
        AnimationEngine.releaseAll()
        val root = File(context.applicationContext.filesDir, ROOT_DIR_NAME)
        return if (root.exists()) root.deleteRecursively() else true
    }

    /**
     * Downloads all required TFLite files for [style] atomically with live [0..1] progress.
     */
    suspend fun downloadStyle(
        context: Context,
        style: AnimationStyle,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = styleDir(context, style)
        if (!dir.exists()) dir.mkdirs()

        val totalExpected = style.totalApproxBytes.coerceAtLeast(1L)
        var downloadedBeforeCurrent = 0L

        try {
            for (spec in style.files) {
                val targetFile = File(dir, spec.fileName)
                if (targetFile.exists() && targetFile.length() > 100_000L && hasValidTfliteHeader(targetFile)) {
                    downloadedBeforeCurrent += spec.approxBytes
                    onProgress((downloadedBeforeCurrent.toFloat() / totalExpected).coerceIn(0f, 0.99f))
                    continue
                }

                val tmpFile = File(dir, "${spec.fileName}.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                downloadSingleFile(spec.url, tmpFile) { fileBytes ->
                    val overall = (downloadedBeforeCurrent + fileBytes).toFloat() / totalExpected
                    onProgress(overall.coerceIn(0f, 0.99f))
                }

                if (!isActive) {
                    tmpFile.delete()
                    return@withContext Result.failure(IOException("Unduhan dibatalkan"))
                }

                if (!hasValidTfliteHeader(tmpFile)) {
                    tmpFile.delete()
                    return@withContext Result.failure(IOException("File model tidak valid"))
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                downloadedBeforeCurrent += spec.approxBytes
            }

            onProgress(1f)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading ${style.id}: ${e.message}", e)
            // Clean partial .tmp files
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            Result.failure(e)
        }
    }

    private fun downloadSingleFile(
        urlString: String,
        destFile: File,
        onBytes: (Long) -> Unit
    ) {
        var currentUrl = urlString
        var redirects = 0
        var conn: HttpURLConnection? = null

        try {
            while (redirects < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ArArShot-Android/3.1")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect tanpa tujuan")
                    conn.disconnect()
                    currentUrl = loc
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("Server merespons HTTP $code")
                }
                break
            }

            val activeConn = conn ?: throw IOException("Gagal membuka koneksi")
            activeConn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var totalRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        onBytes(totalRead)
                    }
                    output.flush()
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Checks TFLite FlatBuffer file identifier ("TFL3" at bytes 4..7).
     */
    fun hasValidTfliteHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            file.inputStream().use { ins ->
                val hdr = ByteArray(8)
                val n = ins.read(hdr)
                n == 8 &&
                    hdr[4] == 'T'.code.toByte() &&
                    hdr[5] == 'F'.code.toByte() &&
                    hdr[6] == 'L'.code.toByte() &&
                    hdr[7] == '3'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }
}
