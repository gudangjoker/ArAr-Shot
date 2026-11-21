package com.ararshot.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BorderStyle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.CollageRenderer
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.model.CollageAspectRatio
import com.ararshot.app.model.CollageBgColor
import com.ararshot.app.model.CollageCellState
import com.ararshot.app.model.CollageLayoutTemplate
import com.ararshot.app.model.CollageLayouts
import com.ararshot.app.model.MAX_COLLAGE_PHOTOS
import com.ararshot.app.ui.components.ColorSwatchDot
import com.ararshot.app.ui.components.PrecisionSlider
import com.ararshot.app.ui.components.PrimaryActionButton
import com.ararshot.app.ui.components.SelectablePill
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CollageControlTab(val label: String, val icon: ImageVector) {
    LAYOUT("Layout", Icons.AutoMirrored.Filled.ViewQuilt),
    RATIO("Rasio", Icons.Default.AspectRatio),
    FRAME("Bingkai", Icons.Default.BorderStyle),
    PHOTOS("Foto", Icons.Default.PhotoLibrary)
}

@Composable
fun CollageStudioScreen(
    viewModel: EnhancerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cells by viewModel.collageCells.collectAsState()

    var activeTab by remember { mutableStateOf(CollageControlTab.LAYOUT) }
    var aspectRatio by remember { mutableStateOf(CollageAspectRatio.SQUARE) }
    var bgColor by remember { mutableStateOf(CollageBgColor.DARK) }
    var spacingDp by remember { mutableFloatStateOf(6f) }
    var outerPaddingDp by remember { mutableFloatStateOf(8f) }
    var cornerRadiusDp by remember { mutableFloatStateOf(8f) }

    val availableTemplates = remember(cells.size) {
        CollageLayouts.templatesFor(cells.size.coerceAtLeast(1))
    }
    var selectedTemplateIndex by remember(cells.size) { mutableIntStateOf(0) }
    val activeTemplate = availableTemplates.getOrElse(selectedTemplateIndex) { availableTemplates.first() }

    var selectedCellIndex by remember { mutableStateOf<Int?>(null) }
    var swapSourceIndex by remember { mutableStateOf<Int?>(null) }
    var replaceTargetIndex by remember { mutableStateOf<Int?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Keep selection valid when cells are added or removed
    LaunchedEffect(cells.size) {
        if (selectedCellIndex != null && selectedCellIndex!! >= cells.size) {
            selectedCellIndex = null
        }
        if (swapSourceIndex != null && swapSourceIndex!! >= cells.size) {
            swapSourceIndex = null
        }
    }

    // Multi-photo picker up to MAX_COLLAGE_PHOTOS (9)
    val pickMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_COLLAGE_PHOTOS)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val loaded = uris.mapNotNull { uri ->
                    try {
                        ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1280)
                    } catch (_: Exception) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    if (loaded.isNotEmpty()) {
                        viewModel.addCollagePhotos(loaded)
                    } else {
                        Toast.makeText(context, "Gagal membuka foto", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Single-photo picker to replace a specific cell
    val replaceSingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val idx = replaceTargetIndex
        replaceTargetIndex = null
        if (uri != null && idx != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val bmp = try {
                    ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1280)
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        viewModel.replaceCollagePhoto(idx, bmp)
                    } else {
                        Toast.makeText(context, "Gagal mengganti foto", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun launchAddPhotos() {
        if (cells.size >= MAX_COLLAGE_PHOTOS) {
            Toast.makeText(context, "Maksimal $MAX_COLLAGE_PHOTOS foto", Toast.LENGTH_SHORT).show()
            return
        }
        pickMultipleLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun exportCollage(share: Boolean) {
        if (cells.isEmpty() || isExporting) return
        isExporting = true
        coroutineScope.launch(Dispatchers.Default) {
            val rendered = CollageRenderer.renderCollageToBitmap(
                cells = cells,
                template = activeTemplate,
                aspectRatio = aspectRatio,
                spacingDp = spacingDp,
                outerPaddingDp = outerPaddingDp,
                cornerRadiusDp = cornerRadiusDp,
                bgColorArgb = bgColor.argb,
                longEdgePx = 1800
            )
            if (share) {
                val uri = ImageUtils.createShareableUri(context, rendered, forcePng = false)
                withContext(Dispatchers.Main) {
                    isExporting = false
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Bagikan kolase"))
                    } else {
                        Toast.makeText(context, "Gagal menyiapkan file", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val ok = ImageUtils.saveBitmapToGallery(
                    context = context,
                    bitmap = rendered,
                    displayNamePrefix = "ArArShot_Collage_${System.currentTimeMillis()}"
                )
                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(
                        context,
                        if (ok) "Kolase tersimpan di Galeri" else "Gagal menyimpan kolase",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
            Text(
                text = if (cells.isEmpty()) "Kolase" else "Kolase (${cells.size}/$MAX_COLLAGE_PHOTOS)",
                style = MaterialTheme.typography.titleMedium,
                color = ArArColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (cells.isNotEmpty()) {
                IconButton(onClick = { exportCollage(share = true) }, enabled = !isExporting) {
                    Icon(Icons.Default.Share, contentDescription = "Bagikan", tint = ArArColors.TextPrimary)
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(ArArColors.BrandGradient)
                        .clickable(enabled = !isExporting) { exportCollage(share = false) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Simpan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Interactive WYSIWYG Collage Canvas Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (cells.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ArArColors.Surface1)
                        .border(1.dp, ArArColors.Border, RoundedCornerShape(18.dp))
                        .clickable { launchAddPhotos() }
                        .padding(vertical = 48.dp, horizontal = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewQuilt,
                        contentDescription = null,
                        tint = ArArColors.AccentCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Pilih hingga $MAX_COLLAGE_PHOTOS foto",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArArColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Geser, zoom, putar & tukar posisi tiap foto secara bebas",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArArColors.TextSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryActionButton(
                        text = "Pilih Foto",
                        icon = Icons.Default.AddPhotoAlternate,
                        onClick = { launchAddPhotos() }
                    )
                }
            } else {
                InteractiveCollageCanvas(
                    cells = cells,
                    template = activeTemplate,
                    aspectRatio = aspectRatio,
                    spacingDp = spacingDp,
                    outerPaddingDp = outerPaddingDp,
                    cornerRadiusDp = cornerRadiusDp,
                    bgColor = Color(bgColor.argb),
                    selectedIndex = selectedCellIndex,
                    swapSourceIndex = swapSourceIndex,
                    onTapCell = { idx ->
                        val swapSrc = swapSourceIndex
                        if (swapSrc != null) {
                            if (swapSrc != idx) {
                                viewModel.swapCollageCells(swapSrc, idx)
                            }
                            swapSourceIndex = null
                            selectedCellIndex = idx
                        } else {
                            selectedCellIndex = if (selectedCellIndex == idx) null else idx
                        }
                    },
                    onTransformCell = { idx, newScale, newPanX, newPanY ->
                        selectedCellIndex = idx
                        viewModel.updateCollageCell(idx) { c ->
                            c.copy(scale = newScale, panX = newPanX, panY = newPanY)
                        }
                    }
                )
            }
        }

        // Contextual Per-Cell Toolbar OR Subtle Gesture Hint
        if (cells.isNotEmpty()) {
            val swapSrc = swapSourceIndex
            val selIdx = selectedCellIndex
            if (swapSrc != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ArArColors.Accent.copy(alpha = 0.16f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Ketuk foto tujuan untuk menukar posisi",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ArArColors.AccentCyan
                    )
                    Text(
                        text = "Batal",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ArArColors.TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .clickable { swapSourceIndex = null }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else if (selIdx != null && selIdx in cells.indices) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ArArColors.Surface2)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cells.size > 1) {
                        CellActionChip("Tukar", Icons.Default.SwapHoriz) {
                            swapSourceIndex = selIdx
                        }
                    }
                    CellActionChip("Putar", Icons.AutoMirrored.Filled.RotateRight) {
                        viewModel.rotateCollageCell(selIdx)
                    }
                    CellActionChip("Ganti", Icons.Default.AddPhotoAlternate) {
                        replaceTargetIndex = selIdx
                        replaceSingleLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    CellActionChip("Reset", Icons.Default.RestartAlt) {
                        viewModel.resetCollageCell(selIdx)
                    }
                    CellActionChip("Hapus", Icons.Default.DeleteOutline, tint = ArArColors.Danger) {
                        viewModel.removeCollagePhotoAt(selIdx)
                        selectedCellIndex = null
                    }
                }
            } else {
                Text(
                    text = "Geser & cubit di dalam kotak untuk atur posisi · Ketuk kotak untuk tukar/putar",
                    fontSize = 11.sp,
                    color = ArArColors.TextMuted,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                )
            }
        }

        // Bottom Control Sheet
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
                when (activeTab) {
                    CollageControlTab.LAYOUT -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            availableTemplates.forEachIndexed { idx, tpl ->
                                LayoutThumbnailItem(
                                    template = tpl,
                                    selected = idx == selectedTemplateIndex,
                                    onClick = { selectedTemplateIndex = idx }
                                )
                            }
                        }
                    }

                    CollageControlTab.RATIO -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CollageAspectRatio.entries.forEach { ratio ->
                                SelectablePill(
                                    label = ratio.label,
                                    selected = aspectRatio == ratio,
                                    onClick = { aspectRatio = ratio }
                                )
                            }
                        }
                    }

                    CollageControlTab.FRAME -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CollageBgColor.entries.forEach { col ->
                                    ColorSwatchDot(
                                        color = Color(col.argb),
                                        selected = bgColor == col,
                                        onClick = { bgColor = col }
                                    )
                                }
                            }
                            PrecisionSlider(
                                label = "Jarak",
                                value = spacingDp,
                                valueRange = 0f..24f,
                                defaultValue = 6f,
                                valueFormatter = { "${it.toInt()}" },
                                onValueChange = { spacingDp = it }
                            )
                            PrecisionSlider(
                                label = "Margin",
                                value = outerPaddingDp,
                                valueRange = 0f..28f,
                                defaultValue = 8f,
                                valueFormatter = { "${it.toInt()}" },
                                onValueChange = { outerPaddingDp = it }
                            )
                            PrecisionSlider(
                                label = "Sudut",
                                value = cornerRadiusDp,
                                valueRange = 0f..28f,
                                defaultValue = 8f,
                                valueFormatter = { "${it.toInt()}" },
                                onValueChange = { cornerRadiusDp = it }
                            )
                        }
                    }

                    CollageControlTab.PHOTOS -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                cells.forEachIndexed { idx, cell ->
                                    val isSel = selectedCellIndex == idx
                                    Box(
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(
                                                width = if (isSel) 2.dp else 1.dp,
                                                color = if (isSel) ArArColors.AccentCyan else ArArColors.Border,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable { selectedCellIndex = idx }
                                    ) {
                                        Image(
                                            bitmap = cell.bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(3.dp)
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.72f))
                                                .clickable { viewModel.removeCollagePhotoAt(idx) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Hapus",
                                                tint = Color.White,
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    }
                                }
                                if (cells.size < MAX_COLLAGE_PHOTOS) {
                                    Box(
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ArArColors.Surface2)
                                            .border(1.dp, ArArColors.Border, RoundedCornerShape(10.dp))
                                            .clickable { launchAddPhotos() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.AddPhotoAlternate,
                                            contentDescription = "Tambah Foto",
                                            tint = ArArColors.AccentCyan,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                            if (cells.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${cells.size} dari $MAX_COLLAGE_PHOTOS foto",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ArArColors.TextSecondary
                                    )
                                    Text(
                                        text = "Hapus Semua",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ArArColors.Danger,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(100))
                                            .clickable {
                                                viewModel.clearCollage()
                                                selectedCellIndex = null
                                                swapSourceIndex = null
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ArArColors.Border.copy(alpha = 0.6f), thickness = 1.dp)

            // Bottom Tab Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CollageControlTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) ArArColors.Surface3 else Color.Transparent)
                            .clickable { activeTab = tab }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) ArArColors.AccentCyan else ArArColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            color = if (selected) ArArColors.TextPrimary else ArArColors.TextSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveCollageCanvas(
    cells: List<CollageCellState>,
    template: CollageLayoutTemplate,
    aspectRatio: CollageAspectRatio,
    spacingDp: Float,
    outerPaddingDp: Float,
    cornerRadiusDp: Float,
    bgColor: Color,
    selectedIndex: Int?,
    swapSourceIndex: Int?,
    onTapCell: (Int) -> Unit,
    onTransformCell: (index: Int, scale: Float, panX: Float, panY: Float) -> Unit
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val targetAspect = aspectRatio.ratio

        val canvasW: Float
        val canvasH: Float
        if (maxW / maxH > targetAspect) {
            canvasH = maxH
            canvasW = maxH * targetAspect
        } else {
            canvasW = maxW
            canvasH = maxW / targetAspect
        }

        val scaleFromDp = minOf(canvasW, canvasH) / 360f
        val spacingPx = spacingDp * scaleFromDp
        val outerPaddingPx = outerPaddingDp * scaleFromDp
        val cornerRadiusPx = cornerRadiusDp * scaleFromDp
        val cornerRadiusActualDp = with(density) { cornerRadiusPx.toDp() }

        val rects = remember(template, canvasW, canvasH, outerPaddingPx, spacingPx) {
            CollageRenderer.computeCellPixelRects(
                cells = template.cells,
                canvasW = canvasW,
                canvasH = canvasH,
                outerPaddingPx = outerPaddingPx,
                spacingPx = spacingPx
            )
        }

        Box(
            modifier = Modifier
                .size(
                    width = with(density) { canvasW.toDp() },
                    height = with(density) { canvasH.toDp() }
                )
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.dp, ArArColors.Border, RoundedCornerShape(12.dp))
        ) {
            val count = minOf(cells.size, rects.size)
            for (i in 0 until count) {
                val cell = cells[i]
                val rect = rects[i]
                val isSelected = selectedIndex == i
                val isSwapSource = swapSourceIndex == i

                InteractiveCollageCell(
                    cell = cell,
                    cellIndex = i,
                    cellW = rect.width(),
                    cellH = rect.height(),
                    offsetX = rect.left.roundToInt(),
                    offsetY = rect.top.roundToInt(),
                    cornerRadiusDp = cornerRadiusActualDp,
                    isSelected = isSelected,
                    isSwapSource = isSwapSource,
                    onTap = { onTapCell(i) },
                    onTransform = { s, px, py -> onTransformCell(i, s, px, py) }
                )
            }
        }
    }
}

@Composable
private fun InteractiveCollageCell(
    cell: CollageCellState,
    cellIndex: Int,
    cellW: Float,
    cellH: Float,
    offsetX: Int,
    offsetY: Int,
    cornerRadiusDp: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    isSwapSource: Boolean,
    onTap: () -> Unit,
    onTransform: (scale: Float, panX: Float, panY: Float) -> Unit
) {
    val density = LocalDensity.current
    val latestCell by rememberUpdatedState(cell)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnTransform by rememberUpdatedState(onTransform)

    val transform = CollageRenderer.computeCoverTransform(
        bitmapW = cell.bitmap.width,
        bitmapH = cell.bitmap.height,
        rotationDegrees = cell.rotationDegrees,
        cellW = cellW,
        cellH = cellH,
        userScale = cell.scale,
        panX = cell.panX,
        panY = cell.panY
    )

    val shape = RoundedCornerShape(cornerRadiusDp)
    val borderColor = when {
        isSwapSource -> ArArColors.Warning
        isSelected -> ArArColors.AccentCyan
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(
                width = with(density) { cellW.toDp() },
                height = with(density) { cellH.toDp() }
            )
            .clip(shape)
            .border(
                width = if (isSelected || isSwapSource) 2.5.dp else 0.dp,
                color = borderColor,
                shape = shape
            )
            .pointerInput(cell.id, cellIndex, cellW, cellH) {
                detectTapGestures(onTap = { latestOnTap() })
            }
            .pointerInput(cell.id, cellIndex, cellW, cellH) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val cur = latestCell
                    val nextScale = (cur.scale * zoom).coerceIn(1f, 4f)
                    val nextT = CollageRenderer.computeCoverTransform(
                        bitmapW = cur.bitmap.width,
                        bitmapH = cur.bitmap.height,
                        rotationDegrees = cur.rotationDegrees,
                        cellW = cellW,
                        cellH = cellH,
                        userScale = nextScale,
                        panX = cur.panX,
                        panY = cur.panY
                    )
                    val curTx = cur.panX * nextT.maxPanX
                    val curTy = cur.panY * nextT.maxPanY
                    val nextPanX = if (nextT.maxPanX > 0.5f) {
                        ((curTx + pan.x) / nextT.maxPanX).coerceIn(-1f, 1f)
                    } else 0f
                    val nextPanY = if (nextT.maxPanY > 0.5f) {
                        ((curTy + pan.y) / nextT.maxPanY).coerceIn(-1f, 1f)
                    } else 0f
                    latestOnTransform(nextScale, nextPanX, nextPanY)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Render raw bitmap at true pixel size so graphicsLayer matches CollageRenderer Matrix 1:1
        val bmpW = with(density) { cell.bitmap.width.toDp() }
        val bmpH = with(density) { cell.bitmap.height.toDp() }
        Image(
            bitmap = cell.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .requiredSize(width = bmpW, height = bmpH)
                .graphicsLayer {
                    scaleX = transform.totalScale
                    scaleY = transform.totalScale
                    rotationZ = cell.rotationDegrees.toFloat()
                    translationX = transform.txPx
                    translationY = transform.tyPx
                }
        )
    }
}

@Composable
private fun CellActionChip(
    label: String,
    icon: ImageVector,
    tint: Color = ArArColors.TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(ArArColors.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@Composable
private fun LayoutThumbnailItem(
    template: CollageLayoutTemplate,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) ArArColors.Surface3 else ArArColors.Surface2)
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) ArArColors.AccentCyan else ArArColors.Border,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(6.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rects = CollageRenderer.computeCellPixelRects(
                    cells = template.cells,
                    canvasW = size.width,
                    canvasH = size.height,
                    outerPaddingPx = 0f,
                    spacingPx = 2.5.dp.toPx()
                )
                val fillCol = if (selected) ArArColors.AccentCyan.copy(alpha = 0.85f) else ArArColors.TextMuted.copy(alpha = 0.7f)
                val rad = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                rects.forEach { r ->
                    drawRoundRect(
                        color = fillCol,
                        topLeft = Offset(r.left, r.top),
                        size = Size(r.width(), r.height()),
                        cornerRadius = rad
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = template.name,
            fontSize = 10.sp,
            color = if (selected) ArArColors.TextPrimary else ArArColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
