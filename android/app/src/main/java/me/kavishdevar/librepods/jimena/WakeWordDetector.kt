package me.kavishdevar.librepods.jimena

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

private const val TAG = "WakeWordDetector"
private val WAKE_NAMES = listOf("jimena", "jime", "yarvis", "jarvis")

/**
 * Offline wake-word detection over Vosk free-form recognition: listens for "oye" followed by
 * a fuzzy match on Jimena's name, same approach as the Windows build (Vosk has no Spanish
 * entries for "Jimena"/"Jarvis" in-grammar, so free dictation + fuzzy match is what works).
 */
class WakeWordDetector(modelDir: File) {
    private val model = Model(modelDir.absolutePath)
    private val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

    /** Feed a chunk of 16-bit mono PCM audio. Returns true the moment the wake word is heard. */
    fun feed(buffer: ByteArray, length: Int): Boolean {
        val gotFinal = recognizer.acceptWaveForm(buffer, length)
        val text = try {
            val json = JSONObject(if (gotFinal) recognizer.result else recognizer.partialResult)
            (json.optString("text").ifBlank { null } ?: json.optString("partial")).lowercase()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo parsear resultado de Vosk", e)
            ""
        }
        if (text.isBlank()) return false
        val heardCue = text.contains("oye") || text.contains("hey")
        val heardName = WAKE_NAMES.any { containsFuzzyWord(text, it) }
        if (heardCue && heardName) {
            recognizer.reset()
            return true
        }
        return false
    }

    fun close() {
        recognizer.close()
        model.close()
    }
}
