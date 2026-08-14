package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import me.kavishdevar.librepods.jimena.JIMENA_LANG_CODES
import me.kavishdevar.librepods.jimena.JimenaPrefs
import me.kavishdevar.librepods.jimena.JimenaService
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle

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
    var translateMode by remember { mutableStateOf(prefs.translateModeEnabled) }
    var sourceLang by remember { mutableStateOf(prefs.translateSourceLang) }
    var targetLang by remember { mutableStateOf(prefs.translateTargetLang) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var targetMenuOpen by remember { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

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
