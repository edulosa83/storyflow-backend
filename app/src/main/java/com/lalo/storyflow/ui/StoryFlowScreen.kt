package com.lalo.storyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lalo.storyflow.model.MediaType
import com.lalo.storyflow.model.StoryMedia
import com.lalo.storyflow.viewmodel.StoryUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryFlowScreen(
    state: StoryUiState,
    onInputChange: (String) -> Unit,
    onBackendUrlInputChange: (String) -> Unit,
    onSaveBackendUrl: () -> Unit,
    onResetBackendUrl: () -> Unit,
    onResolveClick: () -> Unit,
    onDownloadClick: (StoryMedia) -> Unit,
    onConsumeSnackbar: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onConsumeSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "StoryFlow",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Pega URL o usuario. También puedes llegar aquí desde Compartir en Instagram.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL o usuario") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Link, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val pasted = clipboard.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()) {
                                onInputChange(pasted)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Pegar"
                        )
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.backendUrlInput,
                onValueChange = onBackendUrlInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveBackendUrl,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Guardar backend")
                }

                Button(
                    onClick = onResetBackendUrl,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.SettingsBackupRestore, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Restaurar")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Backend activo: ${state.activeBackendUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onResolveClick,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Buscar stories")
            }

            if (!state.source.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Fuente: ${state.source} | Perfil: ${state.normalizedInput}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.items, key = { it.id }) { media ->
                            StoryCard(
                                media = media,
                                onDownloadClick = { onDownloadClick(media) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryCard(
    media: StoryMedia,
    onDownloadClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = media.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "@${media.username}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val mediaLabel = when (media.mediaType) {
                    MediaType.IMAGE -> "Foto"
                    MediaType.VIDEO -> "Video"
                }

                Text(
                    text = "Tipo: $mediaLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )

                if (!media.takenAtIso.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Publicado: ${media.takenAtIso}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDownloadClick) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Descargar")
                    }
                }
            }
        }
    }
}
