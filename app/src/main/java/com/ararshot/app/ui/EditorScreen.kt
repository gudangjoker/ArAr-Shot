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
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
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
import com.ararshot.app.engine.TextFontFamily
import com.ararshot.app.engine.TextOverlayConfig
import com.ararshot.app.engine.TextStyleModel
import com.ararshot.app.ui.components.BeforeAfterSlider
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

    var selectedEnhanceMode by remember { mutableIntStateOf(ImageEnhancerNative.MODE_SUPER_RESOLUTION) }
    var enhanceStrength by remember { mutableFloatStateOf(0.8f) }

    // Background Replace state
    var bgMode by remember { mutableStateOf(BgReplaceMode.TRANSPARENT) }
    var selectedSolidColor by remember { mutableIntStateOf(android.graphics.Color.WHITE) }
    var customBgBmp by remember { mutableStateOf<Bitmap?>(null) }
    var bgThreshold by remember { mutableFloatStateOf(0.5f) }

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
                        if (workingBitmap != null) {
                            val mp = (workingBitmap!!.width * workingBitmap!!.height) / 1_000_000f
                            Text(
                                text = "${workingBitmap!!.width} × ${workingBitmap!!.height} px (${String.format("%.1f", mp)} MP)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.saveToGallery(context) { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Foto disimpan ke Galeri (Pictures/ArArShot)!" else "Gagal menyimpan foto.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Simpan",
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                    .background(Color(0xFF121216))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (originalBitmap != null && enhancedAiBitmap != null && currentTab == EditorTab.AI_ENHANCE) {
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

            // HORIZONTAL SCROLLABLE TAB ROW
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
                    selected = currentTab == EditorTab.BG_REMOVE,
                    onClick = { viewModel.setCurrentTab(EditorTab.BG_REMOVE) },
                    icon = { Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Hapus/Ganti BG", fontSize = 11.sp) }
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
                    icon = { Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(17.dp)) },
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
                    EditorTab.AI_ENHANCE -> {
                        Text("Mode Peningkatan AI", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val modes = listOf(
                            Triple(ImageEnhancerNative.MODE_SUPER_RESOLUTION, "✨ 4x Super-Resolution", "Real-ESRGAN AI"),
                            Triple(ImageEnhancerNative.MODE_COMBINED_ENHANCE, "🚀 Ultra Enhance", "Denoise + 4x AI"),
                            Triple(ImageEnhancerNative.MODE_UNSHARP_MASK, "🔍 Pertajam Detail", "Unsharp Mask"),
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
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Kekuatan Rekonstruksi:", fontSize = 12.sp)
                            Text("${(enhanceStrength * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = enhanceStrength,
                            onValueChange = { enhanceStrength = it },
                            valueRange = 0.3f..1.5f
                        )

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

                    // TAB 2: AI BACKGROUND REMOVER & REPLACER
                    EditorTab.BG_REMOVE -> {
                        Text("AI Background Studio", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Modes Selector
                        val bgModes = listOf(
                            Pair(BgReplaceMode.TRANSPARENT, "✂️ Hapus BG (Transparan)"),
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
                                    fontSize = 13.sp,
                                    fontWeight = if (bgMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                                if (bgMode == mode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Color swatches when SOLID_COLOR is selected
                        if (bgMode == BgReplaceMode.SOLID_COLOR) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Pilih Warna Latar:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            val colors = listOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.BLACK,
                                android.graphics.Color.parseColor("#00E5FF"), // Neon Cyan
                                android.graphics.Color.parseColor("#E0E0E0"), // Minimal Gray
                                android.graphics.Color.parseColor("#FFD700"), // Gold
                                android.graphics.Color.parseColor("#00E676"), // Chroma Green
                                android.graphics.Color.parseColor("#D50000"), // Red
                                android.graphics.Color.parseColor("#304FFE")  // Royal Blue
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                colors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
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

                        if (bgMode == BgReplaceMode.CUSTOM_IMAGE) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { pickCustomBgLauncher.launch("image/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (customBgBmp != null) "Ganti Latar Galeri Terpilih" else "Pilih Latar dari Galeri", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val config = BgReplaceConfig(
                                    mode = bgMode,
                                    solidColor = selectedSolidColor,
                                    customBgBitmap = customBgBmp,
                                    edgeThreshold = bgThreshold
                                )
                                viewModel.runBackgroundReplace(config)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("✨ Eksekusi Hapus / Ganti Latar", fontWeight = FontWeight.Bold)
                        }
                    }

                    // TAB 3: TEXT & TYPOGRAPHY STYLER
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
                                Text("• Format: JPEG / PNG Kualitas Tinggi", fontSize = 12.sp)
                                Text("• Lokasi: Pictures/ArArShot", fontSize = 12.sp)
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
