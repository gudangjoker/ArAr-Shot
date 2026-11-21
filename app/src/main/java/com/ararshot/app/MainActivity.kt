package com.ararshot.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.ui.CollageStudioScreen
import com.ararshot.app.ui.EditorScreen
import com.ararshot.app.ui.HomeScreen
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.ui.theme.ArArShotTheme
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    EDITOR,
    COLLAGE
}

class MainActivity : AppCompatActivity() {

    private val viewModel: EnhancerViewModel by viewModels()
    private var currentScreen by mutableStateOf(AppScreen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Instant registration of AssetManager (models are lazy-loaded on first use)
        lifecycleScope.launch(Dispatchers.IO) {
            ImageEnhancerNative.initEngine(applicationContext)
        }

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            ArArShotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ArArColors.CanvasBg
                ) {
                    BackHandler(enabled = currentScreen != AppScreen.HOME) {
                        currentScreen = AppScreen.HOME
                    }

                    when (currentScreen) {
                        AppScreen.HOME -> HomeScreen(
                            viewModel = viewModel,
                            onNavigateToEditor = { currentScreen = AppScreen.EDITOR },
                            onNavigateToCollage = { currentScreen = AppScreen.COLLAGE }
                        )
                        AppScreen.EDITOR -> EditorScreen(
                            viewModel = viewModel,
                            onNavigateBack = { currentScreen = AppScreen.HOME }
                        )
                        AppScreen.COLLAGE -> CollageStudioScreen(
                            viewModel = viewModel,
                            onNavigateBack = { currentScreen = AppScreen.HOME }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            viewModel.loadFromUri(this, uri)
            currentScreen = AppScreen.EDITOR
        }
    }
}
