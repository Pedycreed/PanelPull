package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.prefs.CustomAiSettings
import com.example.ui.AiAnalysisMode
import com.example.ui.AiUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAnalyzerScreen(
    selectedBitmap: Bitmap?,
    bitmapLabel: String,
    selectedMode: AiAnalysisMode,
    aiState: AiUiState,
    customAiSettings: CustomAiSettings,
    onSelectMode: (AiAnalysisMode) -> Unit,
    onUploadPhotoUri: (android.net.Uri) -> Unit,
    onClearImage: () -> Unit,
    onAnalyzeClick: (String) -> Unit,
    onOpenApiKeySettings: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var promptInput by rememberSaveable { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onUploadPhotoUri(uri)
        }
    }

    val quickPrompts = when (selectedMode) {
        AiAnalysisMode.VISION_FAST -> listOf(
            "Traduzir balões e SFX para PT-BR",
            "Transcrever diálogos em ordem de leitura",
            "Descrever expressões e ação da página"
        )
        AiAnalysisMode.HIGH_THINKING -> listOf(
            "Explicar simbolismo visual e subtexto da cena",
            "Analisar nuances culturais e honoríficos",
            "Teorias e conexões de lore deste momento"
        )
        AiAnalysisMode.GENERAL_ASSISTANT -> listOf(
            "Resumir capítulo e sugerir tags organizacionais",
            "Recomendar manhwas com traço e tema parecidos"
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Leitura & Análise IA",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = onOpenApiKeySettings,
                            label = {
                                Text(
                                    text = if (customAiSettings.isCustomApiActive) {
                                        customAiSettings.provider.shortBadge
                                    } else {
                                        "API Key"
                                    },
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("ai_screen_apikey_chip")
                        )
                    }
                    Text(
                        text = "Envie qualquer captura da galeria ou selecione uma página extraída para traduzir e analisar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Image Upload / Selected Page Card
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Página / Captura",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = bitmapLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selectedBitmap != null) {
                                IconButton(
                                    onClick = onClearImage,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remover imagem")
                                }
                            }
                        }

                        if (selectedBitmap != null) {
                            Image(
                                bitmap = selectedBitmap.asImageBitmap(),
                                contentDescription = "Imagem selecionada para análise",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("ai_upload_photo_button")
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ai_upload_photo))
                        }
                    }
                }
            }

            // Mode Selector (gemini-4-neon, gemini-4-carbon HIGH thinking, gemini-4-hydrogen)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Modo de Inteligência",
                        style = MaterialTheme.typography.titleMedium
                    )
                    AiAnalysisMode.entries.forEach { mode ->
                        val isSelected = mode == selectedMode
                        OutlinedCard(
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                }
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMode(mode) }
                                .testTag("ai_mode_${mode.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = when (mode) {
                                        AiAnalysisMode.VISION_FAST -> Icons.Default.DocumentScanner
                                        AiAnalysisMode.HIGH_THINKING -> Icons.Default.Psychology
                                        AiAnalysisMode.GENERAL_ASSISTANT -> Icons.Default.AutoAwesome
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = mode.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = mode.modelBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Prompt & Quick Chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        quickPrompts.forEach { suggestion ->
                            AssistChip(
                                onClick = { promptInput = suggestion },
                                label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_prompt_input"),
                        label = { Text("Pergunta ou instrução (opcional no modo Visão)") },
                        minLines = 2,
                        maxLines = 4
                    )

                    Button(
                        onClick = { onAnalyzeClick(promptInput) },
                        enabled = aiState !is AiUiState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("ai_run_analysis_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selectedMode == AiAnalysisMode.HIGH_THINKING) {
                                "Analisar com Pensamento Profundo"
                            } else {
                                "Analisar Agora"
                            }
                        )
                    }
                }
            }

            // Result Output
            item {
                when (aiState) {
                    is AiUiState.Idle -> Unit
                    is AiUiState.Loading -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(
                                    text = if (selectedMode == AiAnalysisMode.HIGH_THINKING) {
                                        "Raciocinando em profundidade (gemini-4-carbon)…"
                                    } else {
                                        "Processando página com Gemini…"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    is AiUiState.Streaming, is AiUiState.Success -> {
                        val outputText = when (aiState) {
                            is AiUiState.Streaming -> aiState.text
                            is AiUiState.Success -> aiState.text
                            else -> ""
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (aiState is AiUiState.Streaming) "Gerando resposta…" else "Resultado da Análise",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Análise PanelPull", outputText))
                                            onShowMessage("Texto da análise copiado!")
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar análise")
                                    }
                                }
                                Text(
                                    text = outputText,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    is AiUiState.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = aiState.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
