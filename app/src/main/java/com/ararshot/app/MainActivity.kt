package com.ararshot.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ararshot.app.engine.ImageEnhancerNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val isModelInitialized = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize NCNN Vulkan Models safely in background on app startup
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("MainActivity", "Starting NCNN model initialization in background coroutine...")
                val success = ImageEnhancerNative.initModels(applicationContext)
                withContext(Dispatchers.Main) {
                    isModelInitialized.value = success
                    Log.d("MainActivity", "Model initialization finished. Ready = $success")
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "Caught background initialization error: ${t.message}", t)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArArShotApp(isModelReady = isModelInitialized.value)
                }
            }
        }
    }
}

@Composable
fun ArArShotApp(isModelReady: Boolean) {
    val context = LocalContext.current

    // Determine required permissions based on Android SDK level
    val requiredPermissions = remember {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissions.toTypedArray()
    }

    var cameraGranted by remember { mutableStateOf(isPermissionGranted(context, Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) }
    var storageGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isPermissionGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                isPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        )
    }

    // Runtime Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        cameraGranted = results[Manifest.permission.CAMERA] ?: cameraGranted
        micGranted = results[Manifest.permission.RECORD_AUDIO] ?: micGranted
        storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.READ_MEDIA_IMAGES] ?: storageGranted
        } else {
            results[Manifest.permission.READ_EXTERNAL_STORAGE] ?: storageGranted
        }
    }

    // Auto-prompt permissions on initial launch
    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ArAr Shot",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "AI-Powered Real-ESRGAN & Native C++ Hardware Engine",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            // Native Engine Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isModelReady) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Native Engine Status",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isModelReady) {
                            "Real-ESRGAN NCNN Vulkan Model: ACTIVE (Loaded)"
                        } else {
                            "Native Fallback Engine: ACTIVE (Mathematical Unsharp & Bilateral)"
                        },
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware Permissions Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Hardware Access Permissions",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionItem(label = "📷 Kamera", isGranted = cameraGranted)
                    PermissionItem(label = "🎤 Mikrofon (Audio)", isGranted = micGranted)
                    PermissionItem(label = "📁 Penyimpanan (Media/Foto)", isGranted = storageGranted)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val allGranted = cameraGranted && micGranted && storageGranted
            if (!allGranted) {
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Minta Izin Akses Hardware")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = { /* Pick and enhance image */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Enhance Photo (4x Super-Resolution)")
            }
        }
    }
}

@Composable
fun PermissionItem(label: String, isGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp)
        Text(
            text = if (isGranted) "✓ Diizinkan" else "✗ Belum Diizinkan",
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
