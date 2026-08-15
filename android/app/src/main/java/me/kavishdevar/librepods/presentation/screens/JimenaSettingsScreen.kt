package me.kavishdevar.librepods.presentation.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import me.kavishdevar.librepods.jimena.JIMENA_ACTION_STATUS
import me.kavishdevar.librepods.jimena.JIMENA_EXTRA_LEVEL
import me.kavishdevar.librepods.jimena.JIMENA_EXTRA_STATE
import me.kavishdevar.librepods.jimena.JIMENA_LANG_CODES
import me.kavishdevar.librepods.jimena.JimenaPrefs
import me.kavishdevar.librepods.jimena.JimenaService
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import java.util.Locale

/**
 * Standalone in-app voice assistant: no AirPods gestures involved on purpose (Salvador wants
 * this controlled from within the app only) and works with whichever mic/output is active,
 * not gated to AirPods.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun JimenaSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { JimenaPrefs(context) }

    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var muted by remember { mutableStateOf(prefs.muted) }
    var translateMode by remember { mutableStateOf(prefs.translateModeEnabled) }
    var sourceLang by remember { mutableStateOf(prefs.translateSourceLang) }
    var targetLang by remember { mutableStateOf(prefs.translateTargetLang) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    var voiceMenuOpen by remember { mutableStateOf(false) }

    var level by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Detenida") }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                level = intent?.getFloatExtra(JIMENA_EXTRA_LEVEL, 0f) ?: 0f
                statusText = intent?.getStringExtra(JIMENA_EXTRA_STATE) ?: statusText
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(JIMENA_ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Voices are read from a throwaway TTS instance just for listing/previewing — the live
    // engine picking one for real replies lives in JimenaService.
    var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var previewTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = arrayOfNulls<TextToSpeech>(1)
        engine[0] = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                availableVoices = engine[0]?.voices
                    ?.filter { it.locale.language == "es" }
                    ?.sortedWith(
                        compareByDescending<Voice> { it.isNetworkConnectionRequired }
                            .thenByDescending { it.quality }
                    )
                    ?: emptyList()
            }
        }
        previewTts = engine[0]
        onDispose {
            engine[0]?.stop()
            engine[0]?.shutdown()
        }
    }

    val micPermission = rememberMultiplePermissionsState(
        listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        )
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            prefs.enabled = true
            enabled = true
            JimenaService.start(context)
        } else {
            enabled = false
        }
    }

    val scrollState = rememberScrollState()
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val orbScale by animateFloatAsState(
        targetValue = 1f + (level.coerceIn(0f, 1f) * 0.7f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "jimenaOrbScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            if (enabled) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size((64 * orbScale).dp)
                        .background(
                            (if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline).copy(alpha = 0.85f),
                            CircleShape
                        )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (enabled) statusText else "Jimena está apagada",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilledIconToggleButton(
                checked = muted,
                onCheckedChange = {
                    muted = it
                    prefs.muted = it
                },
                enabled = enabled
            ) {
                Icon(
                    imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (muted) "Micrófono silenciado" else "Silenciar micrófono"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Jimena escucha con el micrófono que esté activo (integrado o cualquier " +
                "audífono conectado) y responde por voz. Se activa y controla desde aquí, " +
                "no con gestos de los audífonos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        StyledList(title = "Groq API key") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    prefs.apiKey = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("gsk_...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Ocultar" else "Ver")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        StyledToggle(
            title = "Asistente",
            label = "Activar Jimena",
            description = if (apiKey.isBlank()) {
                "Agrega tu API key de Groq arriba para poder activarla."
            } else {
                "Wake word \"oye Jimena\", conversación abierta y respuesta por voz."
            },
            checked = enabled,
            enabled = apiKey.isNotBlank(),
            onCheckedChange = { turnOn ->
                if (turnOn && apiKey.isNotBlank()) {
                    micPermission.launchMultiplePermissionRequest()
                } else if (!turnOn) {
                    enabled = false
                    prefs.enabled = false
                    JimenaService.stop(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        StyledList(title = "Voz") {
            Box {
                StyledListItem(
                    name = "Voz de Jimena",
                    description = if (availableVoices.isEmpty()) {
                        "Cargando voces disponibles…"
                    } else {
                        prefs.voiceName.takeIf { it.isNotBlank() } ?: "Automática (mejor disponible)"
                    },
                    onClick = { if (availableVoices.isNotEmpty()) voiceMenuOpen = true },
                )
                DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Automática (mejor disponible)") }, onClick = {
                        prefs.voiceName = ""
                        voiceMenuOpen = false
                    })
                    availableVoices.forEach { voice ->
                        DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                            prefs.voiceName = voice.name
                            voiceMenuOpen = false
                        })
                    }
                }
            }
            StyledListItem(
                name = "Probar voz",
                description = "Reproduce una frase con la voz elegida arriba.",
                onClick = {
                    val engine = previewTts ?: return@StyledListItem
                    val chosen = prefs.voiceName.takeIf { it.isNotBlank() }
                        ?.let { name -> availableVoices.firstOrNull { it.name == name } }
                        ?: availableVoices.firstOrNull()
                    chosen?.let { engine.voice = it }
                    engine.setLanguage(Locale("es", "CO"))
                    engine.speak(
                        "Hola, mi amor, así sueno yo.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "jimena_voice_preview"
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        StyledList(title = "Traducción en vivo") {
            StyledToggle(
                label = "Modo traducción en vivo",
                description = "Mientras esté prendido, Jimena traduce todo lo que oiga sin " +
                    "esperar la wake word.",
                checked = translateMode,
                onCheckedChange = {
                    translateMode = it
                    prefs.translateModeEnabled = it
                }
            )

            Box {
                StyledListItem(
                    name = "Idioma de origen",
                    description = sourceLang,
                    onClick = { sourceMenuOpen = true },
                )
                DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                    JIMENA_LANG_CODES.keys.forEach { lang ->
                        DropdownMenuItem(text = { Text(lang) }, onClick = {
                            sourceLang = lang
                            prefs.translateSourceLang = lang
                            sourceMenuOpen = false
                        })
                    }
                }
            }

            Box {
                StyledListItem(
                    name = "Idioma de destino",
                    description = targetLang,
                    onClick = { targetMenuOpen = true },
                )
                DropdownMenu(expanded = targetMenuOpen, onDismissRequest = { targetMenuOpen = false }) {
                    JIMENA_LANG_CODES.keys.forEach { lang ->
                        DropdownMenuItem(text = { Text(lang) }, onClick = {
                            targetLang = lang
                            prefs.translateTargetLang = lang
                            targetMenuOpen = false
                        })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
