package me.kavishdevar.librepods.jimena

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small Spanish Vosk model on first use (same model family
 * Jimena/YARVIS already uses on Windows), so the wake word works fully offline afterwards.
 */
object VoskModelManager {
    private const val TAG = "VoskModelManager"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-es-0.42"

    fun modelDir(context: Context): File =
        File(context.filesDir, "vosk-model")

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, "conf/model.conf").exists() || File(dir, "am/final.mdl").exists()
    }

    /** Blocking: run off the main thread. Reports 0..100 via [onProgress]. */
    fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}): Result<File> {
        val targetDir = modelDir(context)
        if (isModelReady(context)) return Result.success(targetDir)

        return try {
            targetDir.mkdirs()
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()
            val total = connection.contentLength.coerceAtLeast(1)

            val tempZip = File(context.cacheDir, "vosk-model.zip")
            var downloaded = 0
            connection.inputStream.use { input ->
                tempZip.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress((downloaded * 90 / total).coerceIn(0, 90))
                    }
                }
            }

            unzip(tempZip, context.cacheDir)
            val unpackedRoot = File(context.cacheDir, MODEL_DIR_NAME)
            if (unpackedRoot.exists()) {
                unpackedRoot.copyRecursively(targetDir, overwrite = true)
                unpackedRoot.deleteRecursively()
            }
            tempZip.delete()
            onProgress(100)

            if (isModelReady(context)) Result.success(targetDir)
            else Result.failure(Exception("El modelo se descargó pero no se ve completo"))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo descargar el modelo de Vosk", e)
            Result.failure(e)
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destCanonical + File.separator)) {
                    throw SecurityException("Entrada de zip fuera del directorio destino: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
