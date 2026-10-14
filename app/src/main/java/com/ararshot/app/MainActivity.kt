package com.ararshot.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.ui.CollageStudioScreen
import com.ararshot.app.ui.EditorScreen
import com.ararshot.app.ui.HomeScreen
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

enum class AppScreen {
    HOME,
    EDITOR,
    COLLAGE
}

class MainActivity : AppCompatActivity() {

    private val viewModel: EnhancerViewModel by viewModels()
    private val isModelInitialized = mutableStateOf(false)
    private val initErrorMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install Global Uncaught Exception Handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("ArArShotCrash", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
                val crashFile = File(filesDir, "crash_log.txt")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText("Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n${sw}\n")
            } catch (e: Exception) {
                Log.e("ArArShotCrash", "Failed to write crash log: ${e.message}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Automatically initialize native models in background on startup
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("MainActivity", "Auto-initializing native enhancer engine in background...")
                val success = ImageEnhancerNative.initModels(applicationContext)
                withContext(Dispatchers.Main) {
                    isModelInitialized.value = success
                    if (!success) {
                        initErrorMessage.value = "Hardware Fallback Engine Active"
                    }
                    Log.d("MainActivity", "Native model initialized = $success")
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "Error during auto model init: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    initErrorMessage.value = "Init notice: ${t.localizedMessage ?: t.message}"
                }
            }
        }

        try {
            setContent {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

                        // Android System Back Button Interception
                        BackHandler(enabled = currentScreen != AppScreen.HOME) {
                            currentScreen = AppScreen.HOME
                        }

                        when (currentScreen) {
                            AppScreen.HOME -> {
                                HomeScreen(
                                    viewModel = viewModel,
                                    isModelReady = isModelInitialized.value,
                                    onNavigateToEditor = { currentScreen = AppScreen.EDITOR },
                                    onNavigateToCollage = { currentScreen = AppScreen.COLLAGE }
                                )
                            }
                            AppScreen.EDITOR -> {
                                EditorScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentScreen = AppScreen.HOME }
                                )
                            }
                            AppScreen.COLLAGE -> {
                                CollageStudioScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { currentScreen = AppScreen.HOME }
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Fatal error during setContent: ${t.message}", t)
            val fallbackView = android.widget.TextView(this).apply {
                text = "ArAr Shot Studio (Diagnostic Mode)\n\nCould not load Compose layout: ${t.localizedMessage ?: t.message}\n\nPlease check crash_log.txt in internal storage."
                setPadding(48, 48, 48, 48)
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            setContentView(fallbackView)
        }
    }
}
