package me.kavishdevar.librepods.jimena

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
private const val CHAVITA_PACKAGE = "com.metrolist.music"

/** Broadcast with Jimena's current mic level (0f..~1f) and state text, for the live UI orb. */
const val JIMENA_ACTION_STATUS = "me.kavishdevar.librepods.jimena.STATUS"
const val JIMENA_EXTRA_LEVEL = "level"
const val JIMENA_EXTRA_STATE = "state"

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
            if (prefs.muted) {
                updateNotification("Silenciado")
                delay(200)
                continue
            }
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
        while (running && !prefs.translateModeEnabled && !prefs.muted) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read <= 0) continue
            broadcastStatus(pcmRms(buffer, read).toFloat(), "Esperando: oye Jimena")
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

        val marker = JimenaPersona.MUSIC_SEARCH_MARKER
        if (reply.trim().startsWith(marker)) {
            val query = reply.trim().removePrefix(marker).trim()
            val opened = searchInChavita(query)
            speakAndDrain(
                if (opened) "Buscando $query en Chavita, mi amor." else "No tengo Chavita instalada, mi cielo.",
                Locale("es", "CO")
            )
            return
        }

        speakAndDrain(reply, Locale("es", "CO"))
    }

    /** Fires the same "play from search" deep link Chavita already handles for Google Assistant. */
    private fun searchInChavita(query: String): Boolean {
        return try {
            val uri = Uri.parse("https://music.youtube.com/search").buildUpon()
                .appendQueryParameter("q", query)
                .build()
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(CHAVITA_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Chavita no está instalada", e)
            false
        }
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
            broadcastStatus(pcmRms(buffer, read).toFloat(), "Escuchando…")
            if (recorder.feed(buffer, read)) break
        }
        return if (recorder.hasVoice()) recorder.pcm() else null
    }

    private fun broadcastStatus(level: Float, state: String) {
        sendBroadcast(Intent(JIMENA_ACTION_STATUS).apply {
            putExtra(JIMENA_EXTRA_LEVEL, level)
            putExtra(JIMENA_EXTRA_STATE, state)
            setPackage(packageName)
        })
    }

    /** Speaks [text], then discards a beat of mic audio so TTS echo isn't mistaken for speech. */
    private suspend fun speakAndDrain(text: String, locale: Locale) {
        updateNotification("Hablando…")
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
        val engine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) applyVoice()
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ttsBusy = true }
            override fun onDone(utteranceId: String?) { ttsBusy = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ttsBusy = false }
        })
        tts = engine
    }

    /** Applies the voice Salvador picked in settings, or the best available Spanish one. */
    private fun applyVoice() {
        val engine = tts ?: return
        val voices = engine.voices ?: return
        val chosen = prefs.voiceName
            .takeIf { it.isNotBlank() }
            ?.let { name -> voices.firstOrNull { it.name == name } }
            ?: bestSpanishVoice(voices)
        chosen?.let { engine.voice = it }
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
        broadcastStatus(0f, text)
    }

    companion object {
        /** Highest-quality Spanish voice available, preferring network (neural, smoother-sounding)
         *  voices over the on-device compact ones. Also used by JimenaSettingsScreen's picker. */
        fun bestSpanishVoice(voices: Set<Voice>): Voice? =
            voices
                .filter { it.locale.language == "es" }
                .sortedWith(
                    compareByDescending<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, JimenaService::class.java))
        }
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, JimenaService::class.java))
        }
    }
}
