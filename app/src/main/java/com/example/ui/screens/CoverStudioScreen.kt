package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.StudioAspectRatio
import com.example.data.prefs.CustomAiSettings
import com.example.ui.StudioUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverStudioScreen(
    selectedRatio: StudioAspectRatio,
    referenceBitmap: Bitmap?,
    studioState: StudioUiState,
    customAiSettings: CustomAiSettings,
    onSelectRatio: (StudioAspectRatio) -> Unit,
    onPickReferenceUri: (Uri) -> Unit,
    onClearReference: () -> Unit,
    onGenerateClick: (prompt: String, useProQuality: Boolean) -> Unit,
    onCopyArtwork: (Bitmap) -> Unit,
    onOpenApiKeySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var useProQuality by rememberSaveable { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickReferenceUri(uri)
    }

    val presets = listOf(
        "Capa de manhwa dark fantasy, guerreiro com aura ciano e carmesim, arte digital detalhada",
        "Pôster de mangá seinen em alto contraste preto e branco com hachuras de nanquim",
        "Banner panorâmico webtoon cyberpunk neon à noite, chuva e letreiros brilhantes"
    )

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
                            text = "Estúdio de Capas & Arte",
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
                            modifier = Modifier.testTag("studio_screen_apikey_chip")
                        )
                    }
                    Text(
                        text = "Crie capas personalizadas de coleção ou reestilize páginas com controle exato de proporção.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Explicit Aspect Ratio Picker (1:1, 2:3, 3:2, 3:4, 4:3, 9:16, 16:9, 21:9)
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = null)
                            Text(
                                text = "Proporção da Imagem (${selectedRatio.label})",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(StudioAspectRatio.entries) { ratio ->
                                val selected = ratio == selectedRatio
                                OutlinedCard(
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    ),
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .clickable { onSelectRatio(ratio) }
                                        .testTag("aspect_ratio_${ratio.label}")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val boxW = (24f * (ratio.widthRatio / maxOf(ratio.widthRatio, ratio.heightRatio))).coerceAtLeast(10f)
                                        val boxH = (24f * (ratio.heightRatio / maxOf(ratio.widthRatio, ratio.heightRatio))).coerceAtLeast(10f)
                                        Box(
                                            modifier = Modifier.size(28.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = boxW.dp, height = boxH.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        if (selected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                            )
                                        }
                                        Text(
                                            text = ratio.label,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Modo Qualidade Pro (Studio)",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (useProQuality) "gemini-3-pro-image-preview" else "gemini-nano-banana-2.1 (padrão rápido)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Switch(
                                checked = useProQuality,
                                onCheckedChange = { useProQuality = it },
                                modifier = Modifier.testTag("studio_pro_quality_switch")
                            )
                        }
                    }
                }
            }

            // Optional reference image & Prompt
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
                        if (referenceBitmap != null) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Image(
                                    bitmap = referenceBitmap.asImageBitmap(),
                                    contentDescription = "Imagem de referência",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                )
                                IconButton(
                                    onClick = onClearReference,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(48.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remover referência")
                                }
                            }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    pickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Usar Página como Referência (Opcional)")
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            presets.forEachIndexed { idx, preset ->
                                AssistChip(
                                    onClick = { prompt = preset },
                                    label = { Text("Estilo ${idx + 1}") }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_prompt_input"),
                            label = { Text("Descreva a capa, pôster ou cena") },
                            minLines = 3,
                            maxLines = 5
                        )

                        Button(
                            onClick = { onGenerateClick(prompt, useProQuality) },
                            enabled = studioState !is StudioUiState.Loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("studio_generate_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("${stringResource(R.string.studio_generate_button)} (${selectedRatio.label})")
                        }
                    }
                }
            }

            // Output Area
            item {
                when (studioState) {
                    is StudioUiState.Idle -> Unit
                    is StudioUiState.Loading -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Sintetizando arte em ${selectedRatio.label} (pode levar 30–60s)…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    is StudioUiState.Success -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Arte Gerada (${studioState.aspectRatio.label})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Image(
                                    bitmap = studioState.bitmap.asImageBitmap(),
                                    contentDescription = "Capa gerada pelo estúdio",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(studioState.aspectRatio.widthRatio / studioState.aspectRatio.heightRatio)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                )
                                FilledTonalButton(
                                    onClick = { onCopyArtwork(studioState.bitmap) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("studio_copy_art_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copiar Arte para Área de Transferência")
                                }
                            }
                        }
                    }

                    is StudioUiState.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = studioState.message,
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
