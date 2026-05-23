package com.lalo.storyflow

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lalo.storyflow.config.BackendConfigStore
import com.lalo.storyflow.download.MediaDownloadManager
import com.lalo.storyflow.intent.InstagramInputParser
import com.lalo.storyflow.ui.StoryFlowScreen
import com.lalo.storyflow.ui.StoryFlowTheme
import com.lalo.storyflow.viewmodel.StoryViewModel

class MainActivity : ComponentActivity() {

    private val backendConfigStore by lazy { BackendConfigStore(applicationContext) }

    private val viewModel: StoryViewModel by viewModels {
        StoryViewModel.Factory(
            backendConfigStore = backendConfigStore,
            parser = InstagramInputParser(),
            downloader = MediaDownloadManager(applicationContext)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle()
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {}
            )

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            StoryFlowTheme(darkTheme = isSystemInDarkTheme()) {
                StoryFlowScreen(
                    state = state.value,
                    onInputChange = viewModel::onInputChange,
                    onBackendUrlInputChange = viewModel::onBackendUrlInputChange,
                    onSaveBackendUrl = viewModel::saveBackendUrl,
                    onResetBackendUrl = viewModel::resetBackendUrl,
                    onResolveClick = viewModel::resolveStories,
                    onDownloadClick = viewModel::download,
                    onConsumeSnackbar = viewModel::consumeSnackbar
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        if (!sharedText.isNullOrBlank()) {
            viewModel.processExternalInput(sharedText)
        }
    }
}
