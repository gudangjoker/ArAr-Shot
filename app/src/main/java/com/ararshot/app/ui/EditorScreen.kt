package com.ararshot.app.ui

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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraEnhance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.BgReplaceConfig
import com.ararshot.app.engine.BgReplaceMode
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TextFontFamily
import com.ararshot.app.engine.TextStyleModel
import com.ararshot.app.engine.TextStyler
import com.ararshot.app.ui.components.BeforeAfterSlider
import com.ararshot.app.ui.components.GradientButton
import com.ararshot.app.ui.components.ModernSlider
import com.ararshot.app.ui.components.OptionRow
import com.ararshot.app.ui.theme.ArArColors
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
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()

    var selectedEnhanceMode by remember { mutableIntStateOf(ImageEnhancerNative.MODE_FACE_RESTORE) }
    var enhanceStrength by remember { mutableFloatStateOf(0.85f) }
    var showBeforeAfter by remember { mutableStateOf(true) }

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
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 1536)
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
                            text = "ArAr Studio",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Editor AI offline",
                            fontSize = 10.sp,
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
                    // Undo button
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Urungkan")
                    }

                    // Redo button
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Ulangi")
                    }

                    // Toggle Before/After comparison slider
                    if (originalBitmap != null && workingBitmap != null && workingBitmap != originalBitmap) {
                        IconButton(onClick = { showBeforeAfter = !showBeforeAfter }) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = "Bandingkan Sebelum/Sesudah",
                                tint = if (showBeforeAfter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Reset to original button
                    if (originalBitmap != null && workingBitmap != originalBitmap) {
                        OutlinedButton(
                            onClick = {
                                originalBitmap?.let { viewModel.setSourceBitmap(it) }
                                Toast.makeText(context, "Kembali ke foto awal", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("Atur Ulang", fontSize = 11.sp)
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
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
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
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ArArColors.Ink)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val activeDisplayBitmap = remember(workingBitmap, currentTab, textConfig) {
                    val base = workingBitmap
                    if (base != null && currentTab == EditorTab.TEXT_TYPOGRAPHY && textConfig.text.isNotBlank()) {
                        TextStyler.drawTextOnBitmap(base, textConfig)
                    } else {
                        base
                    }
                }

                if (currentTab == EditorTab.MAGIC_ERASER && workingBitmap != null && inpaintMask != null) {
                    InpaintBrushView(
                        sourceBitmap = workingBitmap!!,
                        maskBitmap = inpaintMask!!,
                        brushRadius = brushRadius,
                        isErasing = isErasingBrush,
                        modifier = Modifier.fillMaxSize(),
                        onMaskUpdated = { maskRevision++ }
                    )
                } else if (showBeforeAfter && originalBitmap != null && enhancedAiBitmap != null &&
                    workingBitmap != originalBitmap && currentTab != EditorTab.TEXT_TYPOGRAPHY &&
                    currentTab != EditorTab.PASSPORT_PHOTO && currentTab != EditorTab.CROP_TRANSFORM
                ) {
                    BeforeAfterSlider(
                        beforeBitmap = originalBitmap!!,
                        afterBitmap = activeDisplayBitmap ?: enhancedAiBitmap!!,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (activeDisplayBitmap != null) {
                    Image(
                        bitmap = activeDisplayBitmap.asImageBitmap(),
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

            // TOOL CHIP BAR (all studio tools)
            ToolChipBar(
                current = currentTab,
                onSelect = { viewModel.setCurrentTab(it) }
            )

            // CONTROLS DRAWER
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(controlScrollState)
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                when (currentTab) {
                    // TAB 1: REMINI FACE RESTORATION & AI 4X SUPER-RESOLUTION
                    EditorTab.AI_ENHANCE -> {
                        Text("Pilihan Restorasi Wajah & AI 4X", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val modes = listOf(
                            Triple(
                                ImageEnhancerNative.MODE_FACE_RESTORE,
                                "👤 Restorasi Wajah & Potret",
                                "Deteksi Titik Wajah + Rekonstruksi AI + Pemulihan Mata & Pori Kulit"
                            ),
                            Triple(
                                ImageEnhancerNative.MODE_SUPER_RESOLUTION,
                                "✨ Resolusi Super 4X Tajam",
                                "Peningkatan Resolusi Neural Real-ESRGAN x4v3"
                            ),
                            Triple(
                                ImageEnhancerNative.MODE_COMBINED_ENHANCE,
                                "🚀 Peningkatan Ekstra + Bebas Noise 4X",
                                "Real-ESRGAN WDN 4X + Pembersihan Bintik Warna + Kontras Mikro"
                            ),
                            Triple(
                                ImageEnhancerNative.MODE_UNSHARP_MASK,
                                "🔍 Pertajam Detail Alami",
                                "Penajaman Adaptif Pencahayaan Tanpa Efek Halo"
                            ),
                            Triple(
                                ImageEnhancerNative.MODE_BILATERAL_DENOISE,
                                "🧼 Bersihkan Bintik & Noise",
                                "Pembersih Bintik ISO & Warna dengan Menjaga Batas Objek"
                            )
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
                                Column(modifier = Modifier.weight(1f)) {
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
                        SliderControl(label = "Tingkat Ketajaman Rekonstruksi AI", value = enhanceStrength, range = 0.3f..1.5f, displayFormat = "%.2fx") {
                            enhanceStrength = it
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.runAiEnhance(context, selectedEnhanceMode, enhanceStrength) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedEnhanceMode == ImageEnhancerNative.MODE_FACE_RESTORE) {
                                    "Jalankan Restorasi Wajah & Potret"
                                } else {
                                    "Jalankan Rekonstruksi AI Sekarang"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // TAB 2: MAGIC ERASER / OBJECT REMOVAL
                    EditorTab.MAGIC_ERASER -> {
                        Text("Penghapus Objek AI", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Usap objek, orang lewat, kabel, atau noda pada foto di atas untuk menghapusnya secara mulus.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Ukuran Kuas", value = brushRadius, range = 8f..70f, displayFormat = "%.0f px") {
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
                                Text("🧽 Hapus Usapan", fontSize = 11.sp)
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
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Hapus Objek Sekarang", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 3: AI DEPTH BOKEH & 3D RELIGHTING
                    EditorTab.DEPTH_BOKEH_3D -> {
                        Text("Efek Bokeh Kedalaman 3D & Pencahayaan Studio", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("📸 1. Efek Blur Lensa DSLR", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        SliderControl(label = "Kekuatan Blur Lensa (Bukaan)", value = bokehBlurRadius, range = 4f..32f, displayFormat = "%.0f px") {
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
                        Text("💡 2. Pencahayaan Studio 3D (Relighting)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        val lightPresets = listOf(
                            Pair("Sian Siber", android.graphics.Color.parseColor("#00E5FF")),
                            Pair("Merah Neon", android.graphics.Color.parseColor("#FF1744")),
                            Pair("Senja Emas", android.graphics.Color.parseColor("#FFB74D")),
                            Pair("Putih Studio", android.graphics.Color.WHITE)
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
                        Text("Hapus Latar Belakang AI, Bayangan & Stiker", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val bgModes = listOf(
                            Pair(BgReplaceMode.TRANSPARENT, "✂️ Hapus Latar (PNG Transparan)"),
                            Pair(BgReplaceMode.STUDIO_SHADOW, "📦 Bayangan Produk 3D (Drop Shadow)"),
                            Pair(BgReplaceMode.STICKER_OUTLINE, "🏷️ Stiker Garis Tepi Putih (Outline)"),
                            Pair(BgReplaceMode.COLOR_SPLASH, "🎨 Warna Subjek (Latar Hitam-Putih)"),
                            Pair(BgReplaceMode.SOLID_COLOR, "🎨 Warna Studio Polos"),
                            Pair(BgReplaceMode.GRADIENT_STUDIO, "🌈 Gradasi Cahaya Studio"),
                            Pair(BgReplaceMode.BLUR_ORIGINAL, "📸 Latar Blur Ala DSLR"),
                            Pair(BgReplaceMode.CUSTOM_IMAGE, "🖼️ Ganti Latar dari Galeri")
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

                        if (bgMode == BgReplaceMode.SOLID_COLOR || bgMode == BgReplaceMode.STUDIO_SHADOW || bgMode == BgReplaceMode.STICKER_OUTLINE) {
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
                            SliderControl(label = "Ketebalan Garis Tepi Stiker", value = stickerOutlineWidth, range = 4f..26f, displayFormat = "%.0f px") {
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
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Terapkan Ganti / Hapus Latar", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("🌟 Fitur Khusus: Tulisan di Belakang Subjek", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Menempatkan tulisan tipografi tepat di belakang badan orang atau produk ala sampul majalah.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.runTextBehindSubject() },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Pasang Tulisan di Belakang Subjek", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // TAB 5: COLORIZE OLD B&W PHOTOS
                    EditorTab.COLORIZE -> {
                        Text("Pewarnaan Foto Hitam-Putih Lawas", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Pewarnaan alami untuk wajah manusia, warna kulit, dedaunan hijau, dan langit biru pada foto kenangan lawas.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Kehangatan Warna Kulit", value = skinWarmth, range = 0.4f..1.6f, displayFormat = "%.2fx") {
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
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🎨 Warnai Foto Hitam-Putih Sekarang", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 6: AI ANIME / CEL-SHADING STYLE
                    EditorTab.ANIME_STYLE -> {
                        Text("Gaya Anime & Ilustrasi Artistik", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Transformasi potret menjadi ilustrasi anime artistik dengan sapuan kuas halus dan kontur tinta yang tegas.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Kehalusan Sapuan Kuas Anime", value = animeRadius.toFloat(), range = 2f..7f, displayFormat = "%.0f") {
                            animeRadius = it.toInt()
                        }
                        SliderControl(label = "Ketegasan Garis Tinta", value = animeEdgeStrength, range = 0.2f..1.2f, displayFormat = "%.2f") {
                            animeEdgeStrength = it
                        }
                        SliderControl(label = "Kecerahan Warna Anime", value = animeVibrancy, range = 0.4f..1.8f, displayFormat = "%.2fx") {
                            animeVibrancy = it
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.runAnimeCelShade(animeRadius, animeEdgeStrength, animeVibrancy) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🌸 Terapkan Gaya Anime Sekarang", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 7: AI PASSPORT / ID PHOTO MAKER & 4R SHEET
                    EditorTab.PASSPORT_PHOTO -> {
                        Text("Pasfoto Resmi (Penataan Wajah Otomatis)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Deteksi posisi wajah otomatis, penggantian warna latar standar, serta susunan lembar cetak 4R rapi.",
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
                        Text("Kamera Lawas CCD & Pendaran Lampu Film", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Nuansa kamera saku analog 2000-an: pendaran cahaya hangat, bintik film analog, dan stempel tanggal khas.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        SliderControl(label = "Pendaran Cahaya Hangat (Halation)", value = halationRadius, range = 4f..24f, displayFormat = "%.0f px") {
                            halationRadius = it
                        }
                        SliderControl(label = "Bintik Film Analog (Grain)", value = filmGrainStrength, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            filmGrainStrength = it
                        }
                        SliderControl(label = "Vinyet Lensa", value = chromaticShift, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            chromaticShift = it
                        }
                        SliderControl(label = "Kehangatan Warna Kamera Lawas", value = vintageWarmth, range = 0.5f..1.6f, displayFormat = "%.2fx") {
                            vintageWarmth = it
                        }

                        OutlinedTextField(
                            value = dateStampText,
                            onValueChange = { dateStampText = it },
                            label = { Text("Cap Tanggal Kamera (Contoh: '98 10 14)") },
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
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("📸 Terapkan Efek Kamera Lawas", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 9: TEXT & TYPOGRAPHY STYLER
                    EditorTab.TEXT_TYPOGRAPHY -> {
                        Text("Studio Teks & Tipografi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = textConfig.text,
                            onValueChange = { viewModel.updateTextOverlay(textConfig.copy(text = it)) },
                            label = { Text("Tuliskan Teks") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Pilih Gaya Tampilan:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                        Text("Pilih Jenis Huruf:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                        SliderControl(label = "Ukuran Huruf", value = textConfig.fontSizeSp, range = 18f..72f, displayFormat = "%.0f sp") {
                            viewModel.updateTextOverlay(textConfig.copy(fontSizeSp = it))
                        }

                        SliderControl(label = "Posisi Horisontal (Kiri/Kanan)", value = textConfig.positionXFraction, range = 0.1f..0.9f, displayFormat = "%.2f") {
                            viewModel.updateTextOverlay(textConfig.copy(positionXFraction = it))
                        }

                        SliderControl(label = "Posisi Vertikal (Atas/Bawah)", value = textConfig.positionYFraction, range = 0.1f..0.9f, displayFormat = "%.2f") {
                            viewModel.updateTextOverlay(textConfig.copy(positionYFraction = it))
                        }

                        SliderControl(label = "Kemiringan Rotasi (Derajat)", value = textConfig.rotationDegrees, range = -45f..45f, displayFormat = "%.0f°") {
                            viewModel.updateTextOverlay(textConfig.copy(rotationDegrees = it))
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.applyTextOverlay() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("✍️ Terapkan Tulisan ke Foto", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 10: COLOR GRADING
                    EditorTab.COLOR_GRADING -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Koreksi Warna Waktu Nyata", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            OutlinedButton(
                                onClick = { viewModel.updateColorGrading(ColorGradingState()) },
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Atur Ulang", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        SliderControl(label = "Pencahayaan (Exposure)", value = colorState.exposure, range = -60f..60f, displayFormat = "%.0f") {
                            viewModel.updateColorGrading(colorState.copy(exposure = it))
                        }
                        SliderControl(label = "Kontras", value = colorState.contrast, range = 0.5f..2.0f, displayFormat = "%.2fx") {
                            viewModel.updateColorGrading(colorState.copy(contrast = it))
                        }
                        SliderControl(label = "Saturasi Warna", value = colorState.saturation, range = 0.0f..2.0f, displayFormat = "%.2fx") {
                            viewModel.updateColorGrading(colorState.copy(saturation = it))
                        }
                        SliderControl(label = "Suhu Warna (Temperature)", value = colorState.temperature, range = -40f..40f, displayFormat = "%.0f") {
                            viewModel.updateColorGrading(colorState.copy(temperature = it))
                        }
                        SliderControl(label = "Vinyet Lensa", value = colorState.vignette, range = 0.0f..1.0f, displayFormat = "%.2f") {
                            viewModel.updateColorGrading(colorState.copy(vignette = it))
                        }
                    }

                    // TAB 11: CROP & TRANSFORM
                    EditorTab.CROP_TRANSFORM -> {
                        Text("Potong & Atur Posisi Foto", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                Text("Balik Horisontal", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Pilihan Rasio Pemotongan:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.cropAspect(1, 1) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("1:1 (Persegi)", fontSize = 11.sp)
                            }
                            Button(onClick = { viewModel.cropAspect(4, 5) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("4:5 (Potret/IG)", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.cropAspect(16, 9) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("16:9 (Lanskap)", fontSize = 11.sp)
                            }
                            Button(onClick = { viewModel.cropAspect(9, 16) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("9:16 (Cerita/Reels)", fontSize = 11.sp)
                            }
                        }
                    }

                    // TAB 12: EXPORT
                    EditorTab.EXPORT -> {
                        val isTransparentPng = remember(workingBitmap) {
                            workingBitmap?.let { ImageUtils.hasTransparency(it) } ?: false
                        }
                        Text("Simpan & Ekspor ke Galeri", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Spesifikasi Foto Hasil:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("• Dimensi: ${workingBitmap?.width ?: 0} × ${workingBitmap?.height ?: 0} piksel", fontSize = 12.sp)
                                Text(
                                    text = if (isTransparentPng) {
                                        "• Format: PNG Transparan Berkualitas Tinggi (32-bit RGBA)"
                                    } else {
                                        "• Format: JPEG Kualitas Ultra (98%)"
                                    },
                                    fontSize = 12.sp
                                )
                                Text("• Lokasi Penyimpanan: Pictures/ArArShot", fontSize = 12.sp)
                                Text("• Mesin Pemrosesan: 100% Offline C++17 OpenMP & NCNN AI", fontSize = 12.sp)
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
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isTransparentPng) "Simpan PNG Transparan ke Galeri" else "Simpan Foto ke Galeri Sekarang",
                                fontWeight = FontWeight.Bold
                            )
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
    ModernSlider(
        label = label,
        value = value,
        range = range,
        displayFormat = displayFormat,
        onValueChange = onValueChange
    )
}

private data class EditorToolChip(val tab: EditorTab, val label: String, val icon: ImageVector)

private val editorToolChips = listOf(
    EditorToolChip(EditorTab.AI_ENHANCE, "Restorasi & 4X", Icons.Default.FaceRetouchingNatural),
    EditorToolChip(EditorTab.MAGIC_ERASER, "Hapus Objek", Icons.Default.Healing),
    EditorToolChip(EditorTab.DEPTH_BOKEH_3D, "Bokeh & 3D", Icons.Default.CameraEnhance),
    EditorToolChip(EditorTab.BG_REMOVE, "Hapus Latar", Icons.Default.Layers),
    EditorToolChip(EditorTab.COLORIZE, "Pewarnaan Lawas", Icons.Default.Gradient),
    EditorToolChip(EditorTab.ANIME_STYLE, "Gaya Anime", Icons.Default.AutoAwesome),
    EditorToolChip(EditorTab.PASSPORT_PHOTO, "Pasfoto Resmi", Icons.Default.ContactPage),
    EditorToolChip(EditorTab.VINTAGE_CCD, "Kamera Lawas", Icons.Default.FilterVintage),
    EditorToolChip(EditorTab.TEXT_TYPOGRAPHY, "Teks & Font", Icons.Default.Title),
    EditorToolChip(EditorTab.COLOR_GRADING, "Koreksi Warna", Icons.Default.Tune),
    EditorToolChip(EditorTab.CROP_TRANSFORM, "Potong & Putar", Icons.Default.CropRotate),
    EditorToolChip(EditorTab.EXPORT, "Simpan & Ekspor", Icons.Default.Download)
)

@Composable
private fun ToolChipBar(current: EditorTab, onSelect: (EditorTab) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        editorToolChips.forEach { chip ->
            val selected = chip.tab == current
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .then(
                        if (selected) Modifier.background(ArArColors.SignatureGradient)
                        else Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    )
                    .clickable { onSelect(chip.tab) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = chip.icon,
                    contentDescription = null,
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = chip.label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
