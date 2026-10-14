package com.ararshot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.BackgroundRemover
import com.ararshot.app.engine.BgReplaceConfig
import com.ararshot.app.engine.BgReplaceMode
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TextFontFamily
import com.ararshot.app.engine.TextOverlayConfig
import com.ararshot.app.engine.TextStyleModel
import com.ararshot.app.ui.components.BeforeAfterSlider
import com.ararshot.app.ui.components.InpaintBrushView
import com.ararshot.app.viewmodel.ColorGradingState
import com.ararshot.app.viewmodel.EditorTab
import com.ararshot.app.viewmodel.EnhancerViewModel
import com.ararshot.app.viewmodel.ProcessState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EnhancerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val workingBitmap by viewModel.workingBitmap.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val enhancedAiBitmap by viewModel.enhancedAiBitmap.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val processState by viewModel.processState.collectAsState()
    val colorState by viewModel.colorState.collectAsState()
    val textConfig by viewModel.textConfig.collectAsState()
    val inpaintMask by viewModel.inpaintMask.collectAsState()

    var selectedEnhanceMode by remember { mutableIntStateOf(ImageEnhancerNative.MODE_SUPER_RESOLUTION) }
    var enhanceStrength by remember { mutableFloatStateOf(0.8f) }

    // Magic Eraser State
    var brushRadius by remember { mutableFloatStateOf(28f) }
    var isErasingBrush by remember { mutableStateOf(false) }
    var maskRevision by remember { mutableIntStateOf(0) }

    // Bokeh & 3D Relighting State
    var bokehBlurRadius by remember { mutableFloatStateOf(16f) }
    var bokehFocusDepth by remember { mutableFloatStateOf(1.0f) }
    var relightX by remember { mutableFloatStateOf(0.5f) }
    var relightY by remember { mutableFloatStateOf(0.2f) }
    var relightZ by remember { mutableFloatStateOf(0.8f) }
    var relightColor by remember { mutableIntStateOf(android.graphics.Color.parseColor("#00E5FF")) }
    var relightIntensity by remember { mutableFloatStateOf(1.0f) }

    // Background Replace state
    var bgMode by remember { mutableStateOf(BgReplaceMode.TRANSPARENT) }
    var selectedSolidColor by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var customBgBmp by remember { mutableStateOf<Bitmap?>(null) }
    var bgThreshold by remember { mutableFloatStateOf(0.5f) }
    var dropShadowRadius by remember { mutableFloatStateOf(16f) }
    var dropShadowAlpha by remember { mutableFloatStateOf(0.65f) }
    var stickerOutlineWidth by remember { mutableFloatStateOf(10f) }

    // Colorize State
    var skinWarmth by remember { mutableFloatStateOf(1.0f) }
    var skyCyan by remember { mutableFloatStateOf(1.0f) }

    // Anime State
    var animeRadius by remember { mutableIntStateOf(4) }
    var animeEdgeStrength by remember { mutableFloatStateOf(0.85f) }
    var animeVibrancy by remember { mutableFloatStateOf(1.0f) }

    // Passport State
    var passportSize by remember { mutableStateOf(PassportSize.SIZE_3X4) }
    var passportBgColor by remember { mutableStateOf(PassportBgColor.INDONESIA_RED) }

    // Vintage CCD State
    var halationRadius by remember { mutableFloatStateOf(14f) }
    var filmGrainStrength by remember { mutableFloatStateOf(0.5f) }
    var chromaticShift by remember { mutableFloatStateOf(0.6f) }
    var vintageWarmth by remember { mutableFloatStateOf(1.0f) }
    var dateStampText by remember { mutableStateOf("'98 10 14") }

    val pickCustomBgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 1024)
                customBgBmp = bmp
                bgMode = BgReplaceMode.CUSTOM_IMAGE
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memuat latar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val controlScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ArAr Studio Pro",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "100% Offline AI Neural Engine",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    // Reset to original button
                    if (originalBitmap != null && workingBitmap != originalBitmap) {
                        OutlinedButton(
                            onClick = {
                                originalBitmap?.let { viewModel.setSourceBitmap(it) }
                                Toast.makeText(context, "Kembali ke foto awal", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text("Reset", fontSize = 11.sp)
                        }
                    }

                    // Save direct button
                    IconButton(
                        onClick = {
                            viewModel.saveToGallery(context) { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Foto berhasil disimpan ke Galeri!" else "Gagal menyimpan foto.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Simpan ke Galeri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // VIEWPORT AREA (Top Half)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.05f)
                    .background(Color(0xFF101014))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (currentTab == EditorTab.MAGIC_ERASER && workingBitmap != null && inpaintMask != null) {
                    InpaintBrushView(
                        sourceBitmap = workingBitmap!!,
                        maskBitmap = inpaintMask!!,
                        brushRadius = brushRadius,
                        isErasing = isErasingBrush,
                        modifier = Modifier.fillMaxSize(),
                        onMaskUpdated = { maskRevision++ }
                    )
                } else if (originalBitmap != null && enhancedAiBitmap != null && currentTab == EditorTab.AI_ENHANCE) {
                    BeforeAfterSlider(
                        beforeBitmap = originalBitmap!!,
                        afterBitmap = workingBitmap ?: enhancedAiBitmap!!,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (workingBitmap != null) {
                    Image(
                        bitmap = workingBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Tidak ada gambar yang dimuat.", color = Color.White)
                }

                // Processing Progress Overlay
                if (processState is ProcessState.Processing) {
                    val state = processState as ProcessState.Processing
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Memproses...",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = state.message,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // HORIZONTAL SCROLLABLE TAB ROW (All 12 Studio Tools)
            ScrollableTabRow(
                selectedTabIndex = currentTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 12.dp
            ) {
                Tab(
                    selected = currentTab == EditorTab.AI_ENHANCE,
                    onClick = { viewModel.setCurrentTab(EditorTab.AI_ENHANCE) },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("AI 4X", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.MAGIC_ERASER,
                    onClick = { viewModel.setCurrentTab(EditorTab.MAGIC_ERASER) },
                    icon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Magic Eraser", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.DEPTH_BOKEH_3D,
                    onClick = { viewModel.setCurrentTab(EditorTab.DEPTH_BOKEH_3D) },
                    icon = { Icon(Icons.Default.BlurOn, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Bokeh & 3D", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.BG_REMOVE,
                    onClick = { viewModel.setCurrentTab(EditorTab.BG_REMOVE) },
                    icon = { Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("AI Cutout & BG", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.COLORIZE,
                    onClick = { viewModel.setCurrentTab(EditorTab.COLORIZE) },
                    icon = { Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Colorize Jadul", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.ANIME_STYLE,
                    onClick = { viewModel.setCurrentTab(EditorTab.ANIME_STYLE) },
                    icon = { Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("AI Anime", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.PASSPORT_PHOTO,
                    onClick = { viewModel.setCurrentTab(EditorTab.PASSPORT_PHOTO) },
                    icon = { Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Pasfoto Resmi", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.VINTAGE_CCD,
                    onClick = { viewModel.setCurrentTab(EditorTab.VINTAGE_CCD) },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Vintage CCD", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.TEXT_TYPOGRAPHY,
                    onClick = { viewModel.setCurrentTab(EditorTab.TEXT_TYPOGRAPHY) },
                    icon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Teks & Font", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.COLOR_GRADING,
                    onClick = { viewModel.setCurrentTab(EditorTab.COLOR_GRADING) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Color Grade", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.CROP_TRANSFORM,
                    onClick = { viewModel.setCurrentTab(EditorTab.CROP_TRANSFORM) },
                    icon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Transform", fontSize = 11.sp) }
                )
                Tab(
                    selected = currentTab == EditorTab.EXPORT,
                    onClick = { viewModel.setCurrentTab(EditorTab.EXPORT) },
                    icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Ekspor", fontSize = 11.sp) }
                )
            }

            // CONTROLS DRAWER
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
                    .verticalScroll(controlScrollState)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                when (currentTab) {
                    // TAB 1: AI 4X SUPER-RESOLUTION
                    EditorTab.AI_ENHANCE -> {
                        Text("Mode Peningkatan AI", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val modes = listOf(
                            Triple(ImageEnhancerNative.MODE_SUPER_RESOLUTION, "✨ 4x Super-Resolution", "Real-ESRGAN NCNN Vulkan"),
                            Triple(ImageEnhancerNative.MODE_COMBINED_ENHANCE, "🚀 Ultra Enhance", "Bilateral Denoise + 4x AI"),
                            Triple(ImageEnhancerNative.MODE_UNSHARP_MASK, "🔍 Pertajam Detail", "Unsharp Mask Native"),
                            Triple(ImageEnhancerNative.MODE_BILATERAL_DENOISE, "🧼 Bersihkan Noise", "Bilateral Denoise")
                        )

                        modes.forEach { (modeId, title, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedEnhanceMode = modeId }
                                    .background(
                                        if (selectedEnhanceMode == modeId) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = title,
                                        fontSize = 13.sp,
                                        fontWeight = if (selectedEnhanceMode == modeId) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(text = desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selectedEnhanceMode == modeId) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SliderControl(label = "Kekuatan Rekonstruksi AI", value = enhanceStrength, range = 0.3f..1.5f, displayFormat = "%.1fx") {
                            enhanceStrength = it
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.runAiEnhance(context, selectedEnhanceMode, enhanceStrength) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Jalankan Rekonstruksi AI 4x", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // TAB 2: MAGIC ERASER / OBJECT REMOVAL
                    EditorTab.MAGIC_ERASER -> {
                        Text("Magic Eraser (Hapus Objek Pengganggu)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Usap objek, orang lewat, kabel, atau noda pada foto di atas.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Ukuran Kuas (Brush Size)", value = brushRadius, range = 8f..70f, displayFormat = "%.0f px") {
                            brushRadius = it
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { isErasingBrush = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = if (!isErasingBrush) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("🖌️ Kuas Objek", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { isErasingBrush = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = if (isErasingBrush) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("🧽 Hapus Kuas", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearInpaintMask() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Bersihkan", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.runMagicEraser(brushRadius.toInt()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("✨ Hapus Objek Sekarang (AI Magic Eraser)", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 3: AI DEPTH BOKEH & 3D RELIGHTING
                    EditorTab.DEPTH_BOKEH_3D -> {
                        Text("AI Depth Bokeh & 3D Relighting", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("📸 1. Optical Lens DSLR Bokeh (f/1.4 - f/8)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        SliderControl(label = "Kekuatan Blur Lensa (Aperture)", value = bokehBlurRadius, range = 4f..32f, displayFormat = "%.0f px") {
                            bokehBlurRadius = it
                        }
                        SliderControl(label = "Jarak Fokus Subjek", value = bokehFocusDepth, range = 0.1f..1.0f, displayFormat = "%.2f") {
                            bokehFocusDepth = it
                        }

                        Button(
                            onClick = { viewModel.runDepthBokeh(bokehBlurRadius, bokehFocusDepth) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.BlurOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Terapkan Bokeh Lensa DSLR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text("💡 2. 3D Studio Relighting (Arah Cahaya Virtual)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        // Light color presets
                        val lightPresets = listOf(
                            Pair("Cyber Cyan", android.graphics.Color.parseColor("#00E5FF")),
                            Pair("Neon Pink", android.graphics.Color.parseColor("#FF1744")),
                            Pair("Golden Hour", android.graphics.Color.parseColor("#FFB74D")),
                            Pair("Studio Putih", android.graphics.Color.WHITE)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            lightPresets.forEach { (name, col) ->
                                Surface(
                                    color = if (relightColor == col) Color(col).copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (relightColor == col) androidx.compose.foundation.BorderStroke(2.dp, Color(col)) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { relightColor = col }
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 10.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        SliderControl(label = "Posisi Cahaya Horisontal (Kiri/Kanan)", value = relightX, range = -0.2f..1.2f, displayFormat = "%.2f") {
                            relightX = it
                        }
                        SliderControl(label = "Posisi Cahaya Vertikal (Atas/Bawah)", value = relightY, range = -0.2f..1.2f, displayFormat = "%.2f") {
                            relightY = it
                        }
                        SliderControl(label = "Intensitas Lampu Studio", value = relightIntensity, range = 0.3f..2.0f, displayFormat = "%.1fx") {
                            relightIntensity = it
                        }

                        Button(
                            onClick = { viewModel.run3DRelighting(relightX, relightY, relightZ, relightColor, relightIntensity) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Terapkan Cahaya Studio 3D", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 4: AI BACKGROUND REMOVER, SHADOW & CUTOUT
                    EditorTab.BG_REMOVE -> {
                        Text("1-Tap AI Cutout, Shadow & Background", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val bgModes = listOf(
                            Pair(BgReplaceMode.TRANSPARENT, "✂️ Hapus BG (PNG Transparan)"),
                            Pair(BgReplaceMode.STUDIO_SHADOW, "📦 3D Product Drop Shadow"),
                            Pair(BgReplaceMode.STICKER_OUTLINE, "🏷️ Stiker Outline Putih (Photoroom)"),
                            Pair(BgReplaceMode.COLOR_SPLASH, "🎨 Splash Color (Latar B&W)"),
                            Pair(BgReplaceMode.SOLID_COLOR, "🎨 Warna Studio Solid"),
                            Pair(BgReplaceMode.GRADIENT_STUDIO, "🌈 Gradien Studio Lighting"),
                            Pair(BgReplaceMode.BLUR_ORIGINAL, "📸 DSLR Bokeh Blur"),
                            Pair(BgReplaceMode.CUSTOM_IMAGE, "🖼️ Ganti Foto dari Galeri")
                        )

                        bgModes.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        bgMode = mode
                                        if (mode == BgReplaceMode.CUSTOM_IMAGE && customBgBmp == null) {
                                            pickCustomBgLauncher.launch("image/*")
                                        }
                                    }
                                    .background(
                                        if (bgMode == mode) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (bgMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                                if (bgMode == mode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        if (bgMode == BgReplaceMode.SOLID_COLOR) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val colors = listOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.BLACK,
                                android.graphics.Color.parseColor("#00E5FF"),
                                android.graphics.Color.parseColor("#E0E0E0"),
                                android.graphics.Color.parseColor("#FFD700"),
                                android.graphics.Color.parseColor("#00E676"),
                                android.graphics.Color.parseColor("#DB1514"),
                                android.graphics.Color.parseColor("#0054A6")
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                colors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color(c))
                                            .border(
                                                width = if (selectedSolidColor == c) 3.dp else 1.dp,
                                                color = if (selectedSolidColor == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                                shape = CircleShape
                                            )
                                            .clickable { selectedSolidColor = c }
                                    )
                                }
                            }
                        }

                        if (bgMode == BgReplaceMode.STUDIO_SHADOW) {
                            SliderControl(label = "Radius Kelembutan Bayangan", value = dropShadowRadius, range = 4f..30f, displayFormat = "%.0f px") {
                                dropShadowRadius = it
                            }
                            SliderControl(label = "Kepekatan Bayangan 3D", value = dropShadowAlpha, range = 0.2f..0.95f, displayFormat = "%.2f") {
                                dropShadowAlpha = it
                            }
                        }

                        if (bgMode == BgReplaceMode.STICKER_OUTLINE) {
                            SliderControl(label = "Ketebalan Outline Stiker", value = stickerOutlineWidth, range = 4f..26f, displayFormat = "%.0f px") {
                                stickerOutlineWidth = it
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val config = BgReplaceConfig(
                                    mode = bgMode,
                                    solidColor = selectedSolidColor,
                                    customBgBitmap = customBgBmp,
                                    edgeThreshold = bgThreshold,
                                    shadowRadius = dropShadowRadius.toInt(),
                                    shadowAlpha = dropShadowAlpha,
                                    outlineWidth = stickerOutlineWidth.toInt()
                                )
                                viewModel.runBackgroundReplace(config)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("✨ Eksekusi Ganti / Hapus Latar", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        // Viral Feature: Text Behind Subject Button
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("🌟 Fitur Viral: Teks di Belakang Subjek", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Menyelipkan tulisan keren tepat di belakang badan orang/produk ala sampul majalah.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.runTextBehindSubject() },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Pasang Teks di Belakang Orang", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // TAB 5: COLORIZE OLD B&W PHOTOS
                    EditorTab.COLORIZE -> {
                        Text("Colorize Old B&W Photos (Warnai Foto Jadul)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Model rekonstruksi warna AI untuk foto lawas kakek-nenek, keluarga, dan lanskap hitam-putih.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Kehangatan Nada Kulit (Skin Tone Warmth)", value = skinWarmth, range = 0.4f..1.6f, displayFormat = "%.2fx") {
                            skinWarmth = it
                        }
                        SliderControl(label = "Saturasi Langit & Alam Hijau", value = skyCyan, range = 0.4f..1.6f, displayFormat = "%.2fx") {
                            skyCyan = it
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.runColorize(skinWarmth, skyCyan) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🎨 Warnai Foto Hitam-Putih Sekarang", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 6: AI ANIME / CEL-SHADING STYLE
                    EditorTab.ANIME_STYLE -> {
                        Text("AI Anime & Cel-Shading Style Transfer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Filter cel-shading Kuwahara multi-kuadran + kontur tinta anime Makoto Shinkai & Studio Ghibli.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Kehalusan Cel-Shading Kuwahara", value = animeRadius.toFloat(), range = 2f..7f, displayFormat = "%.0f") {
                            animeRadius = it.toInt()
                        }
                        SliderControl(label = "Ketegasan Garis Tinta (Ink Outlines)", value = animeEdgeStrength, range = 0.2f..1.0f, displayFormat = "%.2f") {
                            animeEdgeStrength = it
                        }
                        SliderControl(label = "Vibransi Warna Studio Ghibli", value = animeVibrancy, range = 0.4f..1.8f, displayFormat = "%.2fx") {
                            animeVibrancy = it
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.runAnimeCelShade(animeRadius, animeEdgeStrength, animeVibrancy) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🌸 Terapkan Gaya AI Anime / Ghibli", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 7: AI PASSPORT / ID PHOTO MAKER & 4R SHEET
                    EditorTab.PASSPORT_PHOTO -> {
                        Text("AI Pasfoto Resmi & Cetak Grid 4R", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Potong rasio pasfoto resmi, ganti latar belakang standar, dan susun ke kertas cetak 4R.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Pilih Ukuran Pasfoto:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val sizes = PassportSize.values()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sizes.forEach { s ->
                                Surface(
                                    color = if (passportSize == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { passportSize = s }
                                ) {
                                    Text(
                                        text = s.displayName.replace("Pasfoto ", ""),
                                        fontSize = 11.sp,
                                        fontWeight = if (passportSize == s) FontWeight.Bold else FontWeight.Normal,
                                        color = if (passportSize == s) Color.White else MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Pilih Warna Latar Resmi:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val bgColors = PassportBgColor.values()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            bgColors.forEach { bg ->
                                Surface(
                                    color = if (passportBgColor == bg) Color(android.graphics.Color.parseColor(bg.colorHex)).copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (passportBgColor == bg) androidx.compose.foundation.BorderStroke(2.dp, Color(android.graphics.Color.parseColor(bg.colorHex))) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { passportBgColor = bg }
                                ) {
                                    Text(
                                        text = bg.displayName.split(" ")[0],
                                        fontSize = 11.sp,
                                        fontWeight = if (passportBgColor == bg) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.runPassportPhoto(passportSize, passportBgColor) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("🪪 Pasfoto Tunggal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.generatePassport4RSheet(passportSize, passportBgColor) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("🖨️ Cetak Grid 4R", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // TAB 8: VINTAGE CCD & RED HALATION
                    EditorTab.VINTAGE_CCD -> {
                        Text("Vintage CCD Digicam & Red Halation", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Tren kamera saku 2000-an: pendaran merah lembut pada lampu malam, grain analog, & cap tanggal LCD.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Red Halation (Pendaran Merah Lampu)", value = halationRadius, range = 4f..24f, displayFormat = "%.0f px") {
                            halationRadius = it
                        }
                        SliderControl(label = "Film Grain Analog", value = filmGrainStrength, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            filmGrainStrength = it
                        }
                        SliderControl(label = "Chromatic Aberration Lensa", value = chromaticShift, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            chromaticShift = it
                        }
                        SliderControl(label = "Kehangatan Tone CCD", value = vintageWarmth, range = 0.5f..1.6f, displayFormat = "%.2fx") {
                            vintageWarmth = it
                        }

                        OutlinedTextField(
                            value = dateStampText,
                            onValueChange = { dateStampText = it },
                            label = { Text("Cap Tanggal LCD Oranye (Kamera 90-an)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                viewModel.runVintageHalation(
                                    radius = halationRadius,
                                    grain = filmGrainStrength,
                                    chromatic = chromaticShift,
                                    warmth = vintageWarmth,
                                    dateStamp = dateStampText.ifBlank { null }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("📸 Terapkan Filter Vintage CCD & Halation", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 9: TEXT & TYPOGRAPHY STYLER
                    EditorTab.TEXT_TYPOGRAPHY -> {
                        Text("Studio Teks & Tipografi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = textConfig.text,
                            onValueChange = { viewModel.updateTextOverlay(textConfig.copy(text = it)) },
                            label = { Text("Isi Teks") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Pilih Model Gaya Teks:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))

                        val textModels = TextStyleModel.values()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            textModels.forEach { model ->
                                Surface(
                                    color = if (textConfig.styleModel == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        viewModel.updateTextOverlay(textConfig.copy(styleModel = model))
                                    }
                                ) {
                                    Text(
                                        text = model.displayName,
                                        fontSize = 11.sp,
                                        color = if (textConfig.styleModel == model) Color.White else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Pilih Jenis Font:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))

                        val fonts = TextFontFamily.values()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            fonts.forEach { font ->
                                Surface(
                                    color = if (textConfig.font == font) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        viewModel.updateTextOverlay(textConfig.copy(font = font))
                                    }
                                ) {
                                    Text(
                                        text = font.displayName,
                                        fontSize = 11.sp,
                                        color = if (textConfig.font == font) Color.White else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SliderControl(label = "Ukuran Teks (Font Size)", value = textConfig.fontSizeSp, range = 18f..72f, displayFormat = "%.0f sp") {
                            viewModel.updateTextOverlay(textConfig.copy(fontSizeSp = it))
                        }

                        SliderControl(label = "Posisi Vertikal (Atas/Bawah)", value = textConfig.positionYFraction, range = 0.1f..0.9f, displayFormat = "%.2f") {
                            viewModel.updateTextOverlay(textConfig.copy(positionYFraction = it))
                        }

                        SliderControl(label = "Rotasi Teks (Derajat)", value = textConfig.rotationDegrees, range = -45f..45f, displayFormat = "%.0f°") {
                            viewModel.updateTextOverlay(textConfig.copy(rotationDegrees = it))
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.applyTextOverlay() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("✍️ Pasang Teks ke Foto", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 10: COLOR GRADING
                    EditorTab.COLOR_GRADING -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Color Grading Real-Time", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            OutlinedButton(
                                onClick = { viewModel.updateColorGrading(ColorGradingState()) },
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Reset", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        SliderControl(label = "Exposure", value = colorState.exposure, range = -60f..60f, displayFormat = "%.0f") {
                            viewModel.updateColorGrading(colorState.copy(exposure = it))
                        }
                        SliderControl(label = "Contrast", value = colorState.contrast, range = 0.5f..2.0f, displayFormat = "%.2fx") {
                            viewModel.updateColorGrading(colorState.copy(contrast = it))
                        }
                        SliderControl(label = "Saturation", value = colorState.saturation, range = 0.0f..2.0f, displayFormat = "%.2fx") {
                            viewModel.updateColorGrading(colorState.copy(saturation = it))
                        }
                        SliderControl(label = "Temperature", value = colorState.temperature, range = -40f..40f, displayFormat = "%.0f") {
                            viewModel.updateColorGrading(colorState.copy(temperature = it))
                        }
                        SliderControl(label = "Vignette", value = colorState.vignette, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            viewModel.updateColorGrading(colorState.copy(vignette = it))
                        }
                    }

                    // TAB 11: CROP & TRANSFORM
                    EditorTab.CROP_TRANSFORM -> {
                        Text("Transform & Crop Studio", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { viewModel.rotateImage() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rotasi 90°", fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { viewModel.flipHorizontal() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Flip, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Flip Horisontal", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Potong Rasio Aspek (Aspect Ratio):", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.cropAspect(1, 1) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("1:1 (Square)", fontSize = 11.sp)
                            }
                            Button(onClick = { viewModel.cropAspect(4, 5) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("4:5 (IG)", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.cropAspect(16, 9) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("16:9 (Landscape)", fontSize = 11.sp)
                            }
                            Button(onClick = { viewModel.cropAspect(9, 16) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("9:16 (Story)", fontSize = 11.sp)
                            }
                        }
                    }

                    // TAB 12: EXPORT
                    EditorTab.EXPORT -> {
                        Text("Ekspor & Simpan ke Galeri", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Spesifikasi Foto Hasil:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("• Dimensi: ${workingBitmap?.width ?: 0} × ${workingBitmap?.height ?: 0} px", fontSize = 12.sp)
                                Text("• Format: JPEG Kualitas Tinggi (95% Quality)", fontSize = 12.sp)
                                Text("• Lokasi: Pictures/ArArShot", fontSize = 12.sp)
                                Text("• Engine: 100% Offline C++ NDK & NCNN Vulkan", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                viewModel.saveToGallery(context) { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) "Foto berhasil disimpan ke Galeri (Pictures/ArArShot)!" else "Gagal menyimpan foto.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Simpan Foto ke Galeri Sekarang", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SliderControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayFormat: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.sp)
            Text(String.format(displayFormat, value), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(30.dp)
        )
    }
}
