package com.ararshot.app.ui

import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.BgReplaceMode
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.model.ColorAdjustments
import com.ararshot.app.model.EditorTool
import com.ararshot.app.ui.components.InpaintBrushView
import com.ararshot.app.ui.components.InteractiveCropCanvas
import com.ararshot.app.ui.components.PhotoPreviewCanvas
import com.ararshot.app.ui.components.centeredCropRectForAspect
import com.ararshot.app.ui.editor.AdjustPanel
import com.ararshot.app.ui.editor.AnimePanel
import com.ararshot.app.ui.editor.BackgroundPanel
import com.ararshot.app.ui.editor.BokehPanel
import com.ararshot.app.ui.editor.CropAspectOption
import com.ararshot.app.ui.editor.CropPanel
import com.ararshot.app.ui.editor.EnhancePanel
import com.ararshot.app.ui.editor.ErasePanel
import com.ararshot.app.ui.editor.ExportBottomSheet
import com.ararshot.app.ui.editor.FilterPanel
import com.ararshot.app.ui.editor.PassportPanel
import com.ararshot.app.ui.editor.TextPanel
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EnhancerViewModel
import com.ararshot.app.viewmodel.ProcessState
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(
    viewModel: EnhancerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val workingBitmap by viewModel.workingBitmap.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val adjustments by viewModel.adjustments.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val processState by viewModel.processState.collectAsState()
    val filterThumbnails by viewModel.filterThumbnails.collectAsState()
    val bgConfig by viewModel.bgConfig.collectAsState()
    val textConfig by viewModel.textConfig.collectAsState()
    val inpaintMask by viewModel.inpaintMask.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()

    var splitCompare by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // Interactive Crop state
    var cropAspect by remember { mutableStateOf(CropAspectOption.FREE) }
    var cropRect by remember(workingBitmap) { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    var straightenDegrees by remember(workingBitmap) { mutableFloatStateOf(0f) }

    // Erase brush state
    var brushSize by remember { mutableFloatStateOf(28f) }
    var isErasingBrush by remember { mutableStateOf(false) }
    var maskRevision by remember { mutableIntStateOf(0) }

    // Custom background picker
    val customBgPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 1920)
                viewModel.runBackgroundReplace(
                    context,
                    bgConfig.copy(mode = BgReplaceMode.CUSTOM_IMAGE, customBgBitmap = bmp)
                )
            } catch (_: Exception) {
                Toast.makeText(context, "Gagal membuka foto latar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-dismiss transient toast pills
    LaunchedEffect(processState) {
        if (processState is ProcessState.ToastMessage) {
            delay(2200)
            viewModel.dismissToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ArArColors.CanvasBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Slim Top Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = ArArColors.TextPrimary)
            }

            Spacer(Modifier.weight(1f))

            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Urungkan",
                    tint = if (canUndo) ArArColors.TextPrimary else ArArColors.TextMuted.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Ulangi",
                    tint = if (canRedo) ArArColors.TextPrimary else ArArColors.TextMuted.copy(alpha = 0.4f)
                )
            }

            val hasEdits = originalBitmap != null && workingBitmap != null &&
                (workingBitmap !== originalBitmap || !adjustments.isIdentity())

            if (hasEdits && activeTool != EditorTool.CROP && activeTool != EditorTool.ERASE) {
                IconButton(onClick = { splitCompare = !splitCompare }) {
                    Icon(
                        Icons.Default.Compare,
                        contentDescription = "Bandingkan",
                        tint = if (splitCompare) ArArColors.AccentCyan else ArArColors.TextSecondary
                    )
                }
                IconButton(onClick = {
                    splitCompare = false
                    viewModel.resetAll()
                }) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset", tint = ArArColors.TextSecondary)
                }
            }

            Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .background(ArArColors.BrandGradient)
                    .clickable(enabled = workingBitmap != null) { showExportSheet = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Simpan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Photo Canvas (occupies majority of screen!)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val curBmp = workingBitmap
            val origBmp = originalBitmap

            if (curBmp != null && origBmp != null) {
                when (activeTool) {
                    EditorTool.CROP -> {
                        InteractiveCropCanvas(
                            bitmap = curBmp,
                            cropRect = cropRect,
                            straightenDegrees = straightenDegrees,
                            lockedAspectRatio = cropAspect.ratio,
                            onCropRectChange = { cropRect = it }
                        )
                    }
                    EditorTool.ERASE -> {
                        val mask = inpaintMask
                        if (mask != null) {
                            InpaintBrushView(
                                baseBitmap = curBmp,
                                maskBitmap = mask,
                                brushRadiusPx = brushSize,
                                isErasing = isErasingBrush,
                                maskRevision = maskRevision,
                                onMaskUpdated = { maskRevision++ }
                            )
                        } else {
                            PhotoPreviewCanvas(origBmp, curBmp, splitCompare = false)
                        }
                    }
                    else -> {
                        PhotoPreviewCanvas(
                            originalBitmap = origBmp,
                            editedBitmap = curBmp,
                            splitCompare = splitCompare
                        )
                    }
                }
            } else {
                CircularProgressIndicator(color = ArArColors.AccentCyan, strokeWidth = 2.5.dp)
            }

            // Floating slim progress pill (non-intrusive)
            when (val st = processState) {
                is ProcessState.Processing -> {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(100))
                            .background(ArArColors.Surface1.copy(alpha = 0.92f))
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ArArColors.AccentCyan
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(st.message, style = MaterialTheme.typography.bodyMedium, color = ArArColors.TextPrimary)
                    }
                }
                is ProcessState.ToastMessage -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(100))
                            .background(
                                if (st.isError) ArArColors.Danger.copy(alpha = 0.92f)
                                else ArArColors.Surface2.copy(alpha = 0.94f)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(st.message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                }
                ProcessState.Idle -> Unit
            }
        }

        // Contextual Tool Control Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ArArColors.Surface1)
        ) {
            HorizontalDivider(color = ArArColors.Border, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (activeTool) {
                    EditorTool.ADJUST -> AdjustPanel(
                        adjustments = adjustments,
                        onUpdate = { fn -> viewModel.updateAdjustments(fn) },
                        onAutoEnhance = { viewModel.runAutoEnhance() },
                        onDragStart = { viewModel.beginSliderDrag() },
                        onDragEnd = { viewModel.endSliderDrag() },
                        onResetSection = {
                            viewModel.updateAdjustments({ ColorAdjustments(preset = it.preset, presetStrength = it.presetStrength) }, recordUndoImmediately = true)
                        }
                    )
                    EditorTool.FILTER -> FilterPanel(
                        adjustments = adjustments,
                        thumbnails = filterThumbnails,
                        onSelectPreset = { preset ->
                            viewModel.updateAdjustments({ it.copy(preset = preset) }, recordUndoImmediately = true)
                        },
                        onStrengthChange = { str ->
                            viewModel.updateAdjustments({ it.copy(presetStrength = str) })
                        },
                        onDragStart = { viewModel.beginSliderDrag() },
                        onDragEnd = { viewModel.endSliderDrag() }
                    )
                    EditorTool.CROP -> CropPanel(
                        selectedAspect = cropAspect,
                        straightenDegrees = straightenDegrees,
                        onSelectAspect = { opt ->
                            cropAspect = opt
                            val bmp = workingBitmap
                            if (bmp != null) {
                                cropRect = centeredCropRectForAspect(bmp.width, bmp.height, opt.ratio)
                            }
                        },
                        onStraightenChange = { straightenDegrees = it },
                        onRotate90 = { viewModel.rotateImage(90f) },
                        onFlipH = { viewModel.flipHorizontal() },
                        onApplyCrop = {
                            viewModel.applyCropAndStraighten(cropRect, straightenDegrees)
                            cropRect = RectF(0f, 0f, 1f, 1f)
                            straightenDegrees = 0f
                            cropAspect = CropAspectOption.FREE
                        }
                    )
                    EditorTool.ENHANCE -> EnhancePanel(
                        onRunEnhance = { mode, strength ->
                            viewModel.runAiEnhance(context, mode, strength)
                        }
                    )
                    EditorTool.BACKGROUND -> BackgroundPanel(
                        initialConfig = bgConfig,
                        onPickGalleryBg = {
                            customBgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onApply = { cfg ->
                            viewModel.runBackgroundReplace(context, cfg)
                        }
                    )
                    EditorTool.BOKEH -> BokehPanel(
                        onApplyBokeh = { blur, focus ->
                            viewModel.runDepthBokeh(context, blur, focus)
                        },
                        onApplyRelight = { x, y, z, color, intensity ->
                            viewModel.run3DRelighting(context, x, y, z, color, intensity)
                        }
                    )
                    EditorTool.ERASE -> ErasePanel(
                        brushSize = brushSize,
                        isErasing = isErasingBrush,
                        onBrushSizeChange = { brushSize = it },
                        onToggleErasing = { isErasingBrush = it },
                        onClearMask = {
                            viewModel.clearInpaintMask()
                            maskRevision++
                        },
                        onApplyErase = {
                            viewModel.runObjectErase()
                            maskRevision++
                        }
                    )
                    EditorTool.ANIME -> AnimePanel(
                        onApply = { style, str ->
                            viewModel.runAnimeStylize(context, style, str)
                        }
                    )
                    EditorTool.TEXT -> TextPanel(
                        config = textConfig,
                        onConfigChange = { viewModel.updateTextConfig(it) },
                        onApply = { viewModel.applyTextOverlay(context) }
                    )
                    EditorTool.PASSPORT -> PassportPanel(
                        onGenerate = { size, bg, as4R ->
                            viewModel.runPassportPhoto(context, size, bg, as4R)
                        }
                    )
                }
            }

            HorizontalDivider(color = ArArColors.Border.copy(alpha = 0.6f), thickness = 1.dp)

            // Clean Bottom Tool Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditorTool.entries.forEach { tool ->
                    val selected = activeTool == tool
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) ArArColors.Surface3 else Color.Transparent)
                            .clickable {
                                splitCompare = false
                                viewModel.selectTool(tool)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = toolIcon(tool),
                            contentDescription = tool.label,
                            tint = if (selected) ArArColors.AccentCyan else ArArColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tool.label,
                            fontSize = 11.sp,
                            color = if (selected) ArArColors.TextPrimary else ArArColors.TextSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }

    if (showExportSheet) {
        val hasAlpha = remember(workingBitmap) {
            workingBitmap?.let { ImageUtils.hasTransparency(it) } ?: false
        }
        ExportBottomSheet(
            hasTransparency = hasAlpha,
            onDismiss = { showExportSheet = false },
            onSave = { sizeOpt, png, quality ->
                showExportSheet = false
                viewModel.exportToGallery(context, sizeOpt, png, quality) {}
            },
            onShare = { sizeOpt, png ->
                showExportSheet = false
                viewModel.prepareShareUri(context, sizeOpt, png) { uri ->
                    if (uri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (png || hasAlpha) "image/png" else "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Bagikan foto"))
                    }
                }
            }
        )
    }
}

private fun toolIcon(tool: EditorTool): ImageVector = when (tool) {
    EditorTool.ADJUST -> Icons.Default.Tune
    EditorTool.FILTER -> Icons.Default.Filter
    EditorTool.CROP -> Icons.Default.Crop
    EditorTool.ENHANCE -> Icons.Default.FaceRetouchingNatural
    EditorTool.BACKGROUND -> Icons.Default.ContentCut
    EditorTool.BOKEH -> Icons.Default.BlurOn
    EditorTool.ERASE -> Icons.Default.Healing
    EditorTool.ANIME -> Icons.Default.AutoAwesome
    EditorTool.TEXT -> Icons.Default.Title
    EditorTool.PASSPORT -> Icons.Default.Badge
}
