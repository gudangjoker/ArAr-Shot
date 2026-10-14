package com.ararshot.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

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
                        ArArShotApp(
                            isModelReady = isModelInitialized.value,
                            errorMessage = initErrorMessage.value
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Fatal error during setContent: ${t.message}", t)
            val fallbackView = android.widget.TextView(this).apply {
                text = "ArAr Shot v.1.0.3 (Diagnostic Mode)\n\nCould not load Compose layout: ${t.localizedMessage ?: t.message}\n\nPlease check crash_log.txt in internal storage."
                setPadding(48, 48, 48, 48)
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            setContentView(fallbackView)
        }
    }
}

@Composable
fun ArArShotApp(
    isModelReady: Boolean,
    errorMessage: String?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enhancedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showAfter by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableIntStateOf(ImageEnhancerNative.MODE_SUPER_RESOLUTION) }
    var enhanceStrength by remember { mutableFloatStateOf(0.8f) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Hardware Permissions Check
    val requiredPermissions = remember {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        list.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(requiredPermissions.all { isPermissionGranted(context, it) })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    // Photo Pickers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val loaded = loadBitmapFromUri(context, it, maxDim = 800)
                selectedBitmap = loaded
                enhancedBitmap = null
                showAfter = false
                statusMessage = "Foto siap ditingkatkan (${loaded.width} × ${loaded.height} px)"
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memuat foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            selectedBitmap = it
            enhancedBitmap = null
            showAfter = false
            statusMessage = "Foto kamera siap ditingkatkan (${it.width} × ${it.height} px)"
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppLogoImage(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "ArAr Shot v.1.0.3",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "AI Photo Enhancer & 4x Super-Resolution",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Engine & Hardware Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isModelReady) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isModelReady) Icons.Default.CheckCircle else Icons.Default.Shield,
                            contentDescription = "Status",
                            tint = if (isModelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isModelReady) "AI Real-ESRGAN NCNN Aktif" else "Menyiapkan AI Engine...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (!permissionsGranted) {
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Beri Izin", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // MAIN WORKFLOW: IF NO PHOTO SELECTED
            if (selectedBitmap == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Magic",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Tingkatkan Kualitas Foto Anda",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Pilih foto dari galeri atau ambil foto baru untuk ditingkatkan hingga 4x lebih tajam dengan AI Real-ESRGAN.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Collections, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pilih Foto dari Galeri", fontSize = 15.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { cameraLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ambil Foto Baru", fontSize = 15.sp)
                        }
                    }
                }
            } else {
                // PHOTO PREVIEW & COMPARISON CARD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Toggle Tabs when Enhanced
                        if (enhancedBitmap != null) {
                            TabRow(
                                selectedTabIndex = if (showAfter) 1 else 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                Tab(
                                    selected = !showAfter,
                                    onClick = { showAfter = false },
                                    text = { Text("Sebelum (Asli)") }
                                )
                                Tab(
                                    selected = showAfter,
                                    onClick = { showAfter = true },
                                    text = { Text("Sesudah (AI 4x)") }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Display Current Image
                        val currentDisplayBitmap = if (showAfter && enhancedBitmap != null) {
                            enhancedBitmap!!
                        } else {
                            selectedBitmap!!
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp, max = 340.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = currentDisplayBitmap.asImageBitmap(),
                                contentDescription = "Foto",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Dimensions & Status Info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showAfter && enhancedBitmap != null) {
                                    "Hasil: ${enhancedBitmap!!.width} × ${enhancedBitmap!!.height} px (4x)"
                                } else {
                                    "Asli: ${selectedBitmap!!.width} × ${selectedBitmap!!.height} px"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(
                                text = if (enhancedBitmap != null) "✓ Selesai" else "Belum Ditingkatkan",
                                fontSize = 12.sp,
                                color = if (enhancedBitmap != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ENHANCEMENT MODE SELECTOR
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Mode Peningkatan AI",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val modes = listOf(
                            Triple(ImageEnhancerNative.MODE_SUPER_RESOLUTION, "✨ 4x Super-Resolution", "AI Real-ESRGAN 4x"),
                            Triple(ImageEnhancerNative.MODE_COMBINED_ENHANCE, "🚀 Ultra Enhance", "Denoise + AI 4x"),
                            Triple(ImageEnhancerNative.MODE_UNSHARP_MASK, "🔍 Pertajam Detail", "Unsharp Masking"),
                            Triple(ImageEnhancerNative.MODE_BILATERAL_DENOISE, "🧼 Bersihkan Noise", "Bilateral Filter")
                        )

                        modes.forEach { (modeId, title, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isProcessing) { selectedMode = modeId }
                                    .background(
                                        if (selectedMode == modeId) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = title,
                                        fontSize = 13.sp,
                                        fontWeight = if (selectedMode == modeId) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selectedMode == modeId) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Dipilih",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Kekuatan Efek:", fontSize = 12.sp)
                            Text("${(enhanceStrength * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = enhanceStrength,
                            onValueChange = { enhanceStrength = it },
                            valueRange = 0.3f..1.5f,
                            steps = 5,
                            enabled = !isProcessing
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ACTION BUTTONS & PROCESSING SPINNER
                if (isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Sedang Memproses Foto...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "AI Real-ESRGAN sedang merekonstruksi piksel...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // MAIN ACTION BUTTON
                    Button(
                        onClick = {
                            val input = selectedBitmap ?: return@Button
                            coroutineScope.launch(Dispatchers.IO) {
                                isProcessing = true
                                try {
                                    ImageEnhancerNative.initModels(context)
                                    val result = ImageEnhancerNative.enhanceImage(
                                        input = input,
                                        mode = selectedMode,
                                        strength = enhanceStrength
                                    )
                                    withContext(Dispatchers.Main) {
                                        enhancedBitmap = result
                                        showAfter = true
                                        isProcessing = false
                                        Toast.makeText(context, "Foto berhasil ditingkatkan!", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Processing error: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        Toast.makeText(context, "Gagal memproses: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (enhancedBitmap != null) "Tingkatkan Ulang (Mode Lain)" else "🚀 Tingkatkan Kualitas Foto (AI 4x)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // SAVE TO GALLERY BUTTON (WHEN ENHANCED)
                    if (enhancedBitmap != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                enhancedBitmap?.let { bmp ->
                                    val saved = saveBitmapToGallery(context, bmp)
                                    if (saved) {
                                        Toast.makeText(context, "Foto berhasil disimpan ke Galeri (Pictures/ArArShot)!", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Gagal menyimpan foto ke galeri.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("💾 Simpan ke Galeri", fontSize = 14.sp)
                        }
                    }

                    // RESET / CHANGE PHOTO BUTTON
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            selectedBitmap = null
                            enhancedBitmap = null
                            showAfter = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ganti Foto Lain", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun AppLogoImage(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.app_logo),
        contentDescription = "Logo ArAr Shot",
        modifier = modifier
    )
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun loadBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 800): Bitmap {
    val inputStream1 = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open URI")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(inputStream1, null, options)
    inputStream1.close()

    var inSampleSize = 1
    val maxSide = maxOf(options.outWidth, options.outHeight)
    while (maxSide / (inSampleSize * 2) >= maxDim) {
        inSampleSize *= 2
    }

    val inputStream2 = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open URI")
    val decodeOptions = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeStream(inputStream2, null, decodeOptions) ?: throw java.io.IOException("Decode failed")
    inputStream2.close()

    val currentMax = maxOf(decoded.width, decoded.height)
    if (currentMax > maxDim) {
        val scale = maxDim.toFloat() / currentMax
        val scaledW = (decoded.width * scale).toInt()
        val scaledH = (decoded.height * scale).toInt()
        return Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
    }
    return decoded
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val filename = "ArArShot_${System.currentTimeMillis()}.jpg"
    return try {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArArShot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to save image: ${e.message}", e)
        false
    }
}
