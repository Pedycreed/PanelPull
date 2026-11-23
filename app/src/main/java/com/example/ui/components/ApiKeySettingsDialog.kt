package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.prefs.AiApiProvider
import com.example.data.prefs.ApiKeySecureStore
import com.example.data.prefs.CustomAiSettings
import com.example.ui.ApiKeyTestState
import com.example.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeySettingsDialog(
    currentSettings: CustomAiSettings,
    testState: ApiKeyTestState,
    onDismiss: () -> Unit,
    onTestConnection: (CustomAiSettings) -> Unit,
    onSaveSettings: (CustomAiSettings) -> Unit,
    onResetToDefault: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    var selectedProvider by remember(currentSettings) { mutableStateOf(currentSettings.provider) }
    var apiKeyInput by remember(currentSettings) { mutableStateOf(currentSettings.apiKey) }
    var modelInput by remember(currentSettings) { mutableStateOf(currentSettings.modelName) }
    var baseUrlInput by remember(currentSettings) { mutableStateOf(currentSettings.baseUrl) }
    var revealKey by remember { mutableStateOf(false) }

    val draftSettings = remember(selectedProvider, apiKeyInput, modelInput, baseUrlInput) {
        CustomAiSettings(
            provider = selectedProvider,
            apiKey = apiKeyInput.trim(),
            modelName = modelInput.trim(),
            baseUrl = baseUrlInput.trim()
        )
    }

    val isCustomSelected = selectedProvider != AiApiProvider.FIREBASE_DEFAULT
    val baseUrlInvalid = selectedProvider == AiApiProvider.OPENAI_COMPATIBLE &&
        baseUrlInput.isNotBlank() &&
        ApiKeySecureStore.validateHttpsBaseUrl(baseUrlInput) == null

    val canSave = when (selectedProvider) {
        AiApiProvider.FIREBASE_DEFAULT -> true
        AiApiProvider.GEMINI_API -> apiKeyInput.isNotBlank()
        AiApiProvider.OPENAI_COMPATIBLE -> apiKeyInput.isNotBlank() && !baseUrlInvalid
    }

    val modelPresets = when (selectedProvider) {
        AiApiProvider.GEMINI_API -> listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
        AiApiProvider.OPENAI_COMPATIBLE -> listOf("gpt-4o-mini", "gpt-4o", "openai/gpt-4o-mini", "deepseek-chat")
        AiApiProvider.FIREBASE_DEFAULT -> emptyList()
    }

    val endpointPresets = listOf(
        "OpenAI" to "https://api.openai.com/v1",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "Groq" to "https://api.groq.com/openai/v1",
        "DeepSeek" to "https://api.deepseek.com/v1"
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Configurar API Key de IA",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Opcional: use o padrão grátis do app ou sua própria chave",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("apikey_dialog_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Active status banner
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentSettings.isCustomApiActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (currentSettings.isCustomApiActive) Icons.Default.Key else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (currentSettings.isCustomApiActive) EmeraldSuccess else MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (currentSettings.isCustomApiActive) {
                                        "Usando sua API Key (${currentSettings.provider.shortBadge})"
                                    } else {
                                        "Usando IA padrão do app (sem chave)"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (currentSettings.isCustomApiActive) {
                                        "Chave: ${ApiKeySecureStore.maskApiKey(currentSettings.apiKey)} • Modelo: ${currentSettings.effectiveModel}"
                                    } else {
                                        "Firebase AI Logic ativo para filtro inteligente, tradução e estúdio."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Provider selector
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Escolha o provedor de IA",
                            style = MaterialTheme.typography.titleMedium
                        )
                        AiApiProvider.entries.forEach { provider ->
                            val selected = selectedProvider == provider
                            OutlinedCard(
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    }
                                ),
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProvider = provider
                                        if (provider != AiApiProvider.FIREBASE_DEFAULT && modelInput.isBlank()) {
                                            modelInput = provider.defaultModel
                                        }
                                        if (provider == AiApiProvider.OPENAI_COMPATIBLE && baseUrlInput.isBlank()) {
                                            baseUrlInput = provider.defaultBaseUrl
                                        }
                                    }
                                    .testTag("apikey_provider_${provider.name}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (provider == AiApiProvider.FIREBASE_DEFAULT) {
                                            Icons.Default.AutoAwesome
                                        } else {
                                            Icons.Default.Key
                                        },
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = provider.label,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = when (provider) {
                                                AiApiProvider.FIREBASE_DEFAULT ->
                                                    "Funciona automaticamente sem precisar configurar nenhuma chave."
                                                AiApiProvider.GEMINI_API ->
                                                    "Use sua chave do Google AI Studio (suporta texto, visão e geração de capas)."
                                                AiApiProvider.OPENAI_COMPATIBLE ->
                                                    "Use chaves da OpenAI, OpenRouter, Groq ou DeepSeek via HTTPS."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selecionado",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom key fields
                if (isCustomSelected) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { apiKeyInput = it },
                                    label = { Text("Sua API Key") },
                                    placeholder = {
                                        Text(
                                            if (selectedProvider == AiApiProvider.GEMINI_API) "AIza..."
                                            else "sk-..."
                                        )
                                    },
                                    singleLine = true,
                                    visualTransformation = if (revealKey) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        Row {
                                            if (apiKeyInput.isNotEmpty()) {
                                                IconButton(onClick = { apiKeyInput = "" }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Limpar chave")
                                                }
                                            }
                                            IconButton(
                                                onClick = { revealKey = !revealKey },
                                                modifier = Modifier.testTag("apikey_toggle_visibility")
                                            ) {
                                                Icon(
                                                    imageVector = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = if (revealKey) "Ocultar chave" else "Mostrar chave"
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                                    if (clip.isNotBlank()) apiKeyInput = clip.trim()
                                                },
                                                modifier = Modifier.testTag("apikey_paste_button")
                                            ) {
                                                Icon(Icons.Default.ContentPaste, contentDescription = "Colar chave")
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("apikey_input_field")
                                )

                                OutlinedTextField(
                                    value = modelInput,
                                    onValueChange = { modelInput = it },
                                    label = { Text("Modelo (opcional)") },
                                    placeholder = { Text(selectedProvider.defaultModel) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("apikey_model_field")
                                )

                                if (modelPresets.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(modelPresets) { preset ->
                                            FilterChip(
                                                selected = modelInput.trim().equals(preset, ignoreCase = true) ||
                                                    (modelInput.isBlank() && preset == selectedProvider.defaultModel),
                                                onClick = { modelInput = preset },
                                                label = { Text(preset, maxLines = 1) }
                                            )
                                        }
                                    }
                                }

                                if (selectedProvider == AiApiProvider.OPENAI_COMPATIBLE) {
                                    OutlinedTextField(
                                        value = baseUrlInput,
                                        onValueChange = { baseUrlInput = it },
                                        label = { Text("Base URL HTTPS (opcional)") },
                                        placeholder = { Text("https://api.openai.com/v1") },
                                        singleLine = true,
                                        isError = baseUrlInvalid,
                                        supportingText = {
                                            if (baseUrlInvalid) {
                                                Text("Informe uma URL HTTPS pública válida (ex: https://openrouter.ai/api/v1).")
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("apikey_baseurl_field")
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(endpointPresets) { (name, url) ->
                                            FilterChip(
                                                selected = baseUrlInput.trim().trimEnd('/').equals(url, ignoreCase = true),
                                                onClick = { baseUrlInput = url },
                                                label = { Text(name, maxLines = 1) }
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Sua chave é criptografada localmente neste dispositivo via Android Keystore e enviada apenas por HTTPS no cabeçalho da requisição.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                OutlinedButton(
                                    onClick = { onTestConnection(draftSettings) },
                                    enabled = canSave && testState !is ApiKeyTestState.Testing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("apikey_test_button")
                                ) {
                                    if (testState is ApiKeyTestState.Testing) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Testando conexão…")
                                    } else {
                                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Testar chave agora")
                                    }
                                }

                                when (testState) {
                                    is ApiKeyTestState.Success -> {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = testState.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    }
                                    is ApiKeyTestState.Error -> {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = testState.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }

                // Save & Reset Actions
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSaveSettings(draftSettings) },
                            enabled = canSave,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("apikey_save_button")
                        ) {
                            Text(
                                text = if (selectedProvider == AiApiProvider.FIREBASE_DEFAULT) {
                                    "Usar IA padrão do app"
                                } else {
                                    "Salvar minha API Key"
                                }
                            )
                        }

                        if (currentSettings.isCustomApiActive) {
                            TextButton(
                                onClick = onResetToDefault,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("apikey_reset_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Remover chave salva e voltar ao padrão")
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
