package com.lalo.storyflow.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lalo.storyflow.config.BackendConfigStore
import com.lalo.storyflow.download.MediaDownloadManager
import com.lalo.storyflow.intent.InstagramInputParser
import com.lalo.storyflow.model.StoryMedia
import com.lalo.storyflow.network.NetworkModule
import com.lalo.storyflow.network.StoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoryUiState(
    val input: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val source: String? = null,
    val normalizedInput: String? = null,
    val items: List<StoryMedia> = emptyList(),
    val snackbarMessage: String? = null,
    val backendUrlInput: String = "",
    val activeBackendUrl: String = ""
)

class StoryViewModel(
    private val backendConfigStore: BackendConfigStore,
    private val parser: InstagramInputParser,
    private val downloader: MediaDownloadManager
) : ViewModel() {

    private var repository: StoryRepository = StoryRepository(
        NetworkModule.storyApi(backendConfigStore.getBaseUrl())
    )

    private val _uiState = MutableStateFlow(
        StoryUiState(
            backendUrlInput = backendConfigStore.getBaseUrl(),
            activeBackendUrl = backendConfigStore.getBaseUrl()
        )
    )
    val uiState: StateFlow<StoryUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) {
        _uiState.update {
            it.copy(
                input = value,
                error = null
            )
        }
    }

    fun onBackendUrlInputChange(value: String) {
        _uiState.update {
            it.copy(backendUrlInput = value)
        }
    }

    fun saveBackendUrl() {
        val typedUrl = _uiState.value.backendUrlInput

        runCatching { backendConfigStore.saveBaseUrl(typedUrl) }
            .onSuccess { normalized ->
                repository = StoryRepository(NetworkModule.storyApi(normalized))
                _uiState.update {
                    it.copy(
                        backendUrlInput = normalized,
                        activeBackendUrl = normalized,
                        snackbarMessage = "Backend actualizado"
                    )
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        snackbarMessage = throwable.message ?: "URL de backend inválida"
                    )
                }
            }
    }

    fun resetBackendUrl() {
        val fallback = backendConfigStore.resetToDefault()
        repository = StoryRepository(NetworkModule.storyApi(fallback))

        _uiState.update {
            it.copy(
                backendUrlInput = fallback,
                activeBackendUrl = fallback,
                snackbarMessage = "Se restauró la URL por defecto"
            )
        }
    }

    fun processExternalInput(raw: String?) {
        val incoming = raw?.trim().orEmpty()
        if (incoming.isBlank()) return

        val normalized = parser.normalize(incoming)
        _uiState.update {
            it.copy(
                input = normalized,
                error = null
            )
        }

        resolveStories()
    }

    fun resolveStories() {
        val currentInput = _uiState.value.input.trim()
        if (currentInput.isBlank()) {
            _uiState.update { it.copy(error = "Pega un usuario o URL de Instagram") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    error = null,
                    items = emptyList()
                )
            }

            repository.resolveStories(currentInput)
                .onSuccess { resolved ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            source = resolved.source,
                            normalizedInput = resolved.normalizedInput,
                            items = resolved.items,
                            error = if (resolved.items.isEmpty()) {
                                "No se encontraron stories activas para este perfil."
                            } else {
                                null
                            }
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: "No se pudo consultar el backend"
                        )
                    }
                }
        }
    }

    fun download(media: StoryMedia) {
        downloader.enqueue(media)
            .onSuccess {
                _uiState.update {
                    it.copy(snackbarMessage = "Descarga iniciada en Descargas/StoryFlow")
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(snackbarMessage = throwable.message ?: "No se pudo iniciar la descarga")
                }
            }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    class Factory(
        private val backendConfigStore: BackendConfigStore,
        private val parser: InstagramInputParser,
        private val downloader: MediaDownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StoryViewModel::class.java)) {
                return StoryViewModel(backendConfigStore, parser, downloader) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
