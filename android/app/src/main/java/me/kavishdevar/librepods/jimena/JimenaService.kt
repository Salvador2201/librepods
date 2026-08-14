package me.kavishdevar.librepods.jimena

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.MainActivity
import org.vosk.LibVosk
import org.vosk.LogLevel
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "JimenaService"
private const val NOTIF_CHANNEL_ID = "jimena"
private const val NOTIF_ID = 4242
private const val BUFFER_MS = 100
private val BUFFER_SIZE = SAMPLE_RATE / 1000 * BUFFER_MS * 2 // 16-bit mono
private const val MAX_HISTORY = 12
private const val TTS_TIMEOUT_MS = 15000L

val JIMENA_LANG_CODES = mapOf(
    "Español" to "es", "Inglés" to "en", "Portugués" to "pt",
    "Francés" to "fr", "Italiano" to "it", "Alemán" to "de",
)
val JIMENA_LOCALES = mapOf(
    "Español" to Locale("es", "CO"), "Inglés" to Locale.US, "Portugués" to Locale("pt", "BR"),
    "Francés" to Locale.FRENCH, "Italiano" to Locale.ITALIAN, "Alemán" to Locale.GERMAN,
)

/**
 * Jimena: foreground service that owns a single persistent AudioRecord stream (opened once,
 * never closed while active) and either waits for the "oye Jimena" wake word or runs a live
 * translation loop, calling Groq for STT/LLM and Android TTS for the reply. Uses whatever
 * mic/output Android currently has active — no AirPods-specific gating.
 */
@OptIn(ExperimentalMaterial3Api::class)
class JimenaService : Service() {

    private lateinit var prefs: JimenaPrefs
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    @Volatile private var running = false
    @Volatile private var ttsBusy = false

    private val history = ArrayDeque<GroqClient.ChatMessage>()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        prefs = JimenaPrefs(this)
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Iniciando…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        if (!running) {
            running = true
            loopJob = serviceScope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        stopAudio()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        updateNotification("Descargando modelo de voz…")
        val modelDir = VoskModelManager.ensureModel(this) { pct ->
            updateNotification("Descargando modelo de voz… $pct%")
        }.getOrElse {
            updateNotification("Error con el modelo: ${it.message}")
            return
        }

        try {
            initAudioRecord()
            initTts()
            wakeWordDetector = WakeWordDetector(modelDir)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inicializar Jimena", e)
            updateNotification("Error: ${e.message}")
            return
        }

        audioRecord?.startRecording()
        val buffer = ByteArray(BUFFER_SIZE)

        while (running) {
            if (prefs.translateModeEnabled) {
                updateNotification("Traduciendo en vivo (${prefs.translateSourceLang} → ${prefs.translateTargetLang})…")
                runTranslateTurn(buffer)
            } else {
                updateNotification("Esperando: oye Jimena")
                val heard = waitForWakeWord(buffer)
                if (heard && running) {
                    updateNotification("Escuchando…")
                    runConversationTurn(buffer)
                }
            }
        }
    }

    private fun waitForWakeWord(buffer: ByteArray): Boolean {
        val detector = wakeWordDetector ?: return false
        while (running && !prefs.translateModeEnabled) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read <= 0) continue
            if (detector.feed(buffer, read)) return true
        }
        return false
    }

    private suspend fun runConversationTurn(buffer: ByteArray) {
        val pcm = recordUtterance(buffer)
        if (pcm == null || pcm.isEmpty()) return
        updateNotification("Pensando…")

        val wav = File(cacheDir, "jimena_${UUID.randomUUID()}.wav")
        writeWavFile(pcm, wav)
        val transcript = GroqClient.transcribe(prefs.apiKey, wav, "es").getOrNull()
        wav.delete()
        if (transcript.isNullOrBlank()) return

        history.addLast(GroqClient.ChatMessage("user", transcript))
        trimHistory()

        val messages = listOf(GroqClient.ChatMessage("system", JimenaPersona.SYSTEM_PROMPT)) + history
        val reply = GroqClient.chat(prefs.apiKey, messages).getOrElse {
            "Ay mi amor, se me cayó la conexión, intenta otra vez."
        }
        history.addLast(GroqClient.ChatMessage("assistant", reply))
        trimHistory()

        speakAndDrain(reply, Locale("es", "CO"))
    }

    private suspend fun runTranslateTurn(buffer: ByteArray) {
        val pcm = recordUtterance(buffer)
        if (pcm == null || pcm.isEmpty()) return
        updateNotification("Traduciendo…")

        val wav = File(cacheDir, "jimena_tr_${UUID.randomUUID()}.wav")
        writeWavFile(pcm, wav)
        val sourceCode = JIMENA_LANG_CODES[prefs.translateSourceLang] ?: "es"
        val transcript = GroqClient.transcribe(prefs.apiKey, wav, sourceCode).getOrNull()
        wav.delete()
        if (transcript.isNullOrBlank()) return

        val messages = listOf(
            GroqClient.ChatMessage(
                "system",
                JimenaPersona.translationSystemPrompt(prefs.translateSourceLang, prefs.translateTargetLang)
            ),
            GroqClient.ChatMessage("user", transcript),
        )
        val translation = GroqClient.chat(prefs.apiKey, messages, temperature = 0.2).getOrNull()
        if (!translation.isNullOrBlank()) {
            val targetLocale = JIMENA_LOCALES[prefs.translateTargetLang] ?: Locale.US
            speakAndDrain(translation, targetLocale)
        }
    }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun recordUtterance(buffer: ByteArray): ByteArray? {
        val recorder = UtteranceRecorder()
        while (running) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read <= 0) continue
            if (recorder.feed(buffer, read)) break
        }
        return if (recorder.hasVoice()) recorder.pcm() else null
    }

    /** Speaks [text], then discards a beat of mic audio so TTS echo isn't mistaken for speech. */
    private suspend fun speakAndDrain(text: String, locale: Locale) {
        speak(text, locale)
        val drain = ByteArray(BUFFER_SIZE)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 400) {
            audioRecord?.read(drain, 0, drain.size)
        }
    }

    private suspend fun speak(text: String, locale: Locale) = withContext(Dispatchers.Main) {
        val engine = tts ?: return@withContext
        engine.setLanguage(locale)
        ttsBusy = true
        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (queued != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS rechazó el utterance (code=$queued), no hay callback que esperar")
            ttsBusy = false
            return@withContext
        }
        val start = System.currentTimeMillis()
        while (ttsBusy && System.currentTimeMillis() - start < TTS_TIMEOUT_MS) delay(120)
        if (ttsBusy) {
            Log.w(TAG, "TTS nunca confirmó onDone/onError, se destrabó el loop por timeout")
            ttsBusy = false
        }
    }

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, BUFFER_SIZE) * 4
        )
        audioRecord = record
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { setEnabled(true) }
        }
    }

    private fun initTts() {
        val engine = TextToSpeech(this) { }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ttsBusy = true }
            override fun onDone(utteranceId: String?) { ttsBusy = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ttsBusy = false }
        })
        tts = engine
    }

    private fun stopAudio() {
        // The loop coroutine may still be blocked inside audioRecord.read() on another thread
        // when this runs (onDestroy doesn't wait for it to exit); stop()/release() racing that
        // read is undefined per the AudioRecord docs, so guard against the resulting exception.
        try {
            noiseSuppressor?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar el AudioRecord", e)
        }
        noiseSuppressor = null
        audioRecord = null
        wakeWordDetector?.close()
        wakeWordDetector = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "Jimena", NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Jimena")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, JimenaService::class.java))
        }
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, JimenaService::class.java))
        }
    }
}
