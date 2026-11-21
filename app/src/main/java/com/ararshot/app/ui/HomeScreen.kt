package com.ararshot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ararshot.app.R
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.model.AnimationStyle
import com.ararshot.app.model.EditorTool
import com.ararshot.app.model.MAX_COLLAGE_PHOTOS
import com.ararshot.app.ui.components.AnimationModelManagerSheet
import com.ararshot.app.ui.components.PrimaryActionButton
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ToolShortcutItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    viewModel: EnhancerViewModel,
    onNavigateToEditor: () -> Unit,
    onNavigateToCollage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animationModelsState by viewModel.animationModelsState.collectAsState()
    val existingCollageCells by viewModel.collageCells.collectAsState()

    var showModelManager by remember { mutableStateOf(false) }
    var pendingTool by remember { mutableStateOf(EditorTool.ADJUST) }

    LaunchedEffect(Unit) {
        viewModel.refreshAnimationModels(context)
    }

    // Single-photo picker routed directly to the tapped tool (zero storage permission needed)
    val singlePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadFromUri(context, it, initialTool = pendingTool)
            onNavigateToEditor()
        }
    }

    // Multi-photo picker (1..9 photos) dedicated to Collage shortcut
    val collageMultiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_COLLAGE_PHOTOS)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val bitmaps = uris.take(MAX_COLLAGE_PHOTOS).mapNotNull { uri ->
                    try {
                        ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1280)
                    } catch (_: Exception) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    if (bitmaps.isNotEmpty()) {
                        viewModel.clearCollage()
                        viewModel.addCollagePhotos(bitmaps)
                        onNavigateToCollage()
                    } else {
                        Toast.makeText(context, "Gagal membuka foto yang dipilih", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Full-resolution camera capture via FileProvider
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.loadFromUri(context, uri, initialTool = EditorTool.ADJUST)
            onNavigateToEditor()
        } else {
            pendingCameraFile?.delete()
        }
        pendingCameraFile = null
        pendingCameraUri = null
    }

    fun launchCamera() {
        try {
            val (file, uri) = ImageUtils.createCameraOutputUri(context)
            pendingCameraFile = file
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (_: Exception) {
            Toast.makeText(context, "Kamera tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(context, "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
    }

    fun openPickerFor(tool: EditorTool) {
        pendingTool = tool
        singlePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val installedModelsCount = animationModelsState.installedIds.size
    val totalModelsCount = AnimationStyle.entries.size

    val shortcuts = listOf(
        ToolShortcutItem(
            id = "enhance",
            title = "Tingkatkan",
            subtitle = "Anti-noise, wajah & 4X",
            icon = Icons.Default.AutoAwesome,
            accentColor = Color(0xFF38BDF8),
            onClick = { openPickerFor(EditorTool.ENHANCE) }
        ),
        ToolShortcutItem(
            id = "background",
            title = "Hapus Latar",
            subtitle = "Potong subjek & ganti latar",
            icon = Icons.Default.ContentCut,
            accentColor = Color(0xFFA78BFA),
            onClick = { openPickerFor(EditorTool.BACKGROUND) }
        ),
        ToolShortcutItem(
            id = "animation",
            title = "Animasi",
            subtitle = "3D, kartun & ilustrasi",
            icon = Icons.Default.Animation,
            accentColor = Color(0xFFF472B6),
            onClick = { openPickerFor(EditorTool.ANIMATION) }
        ),
        ToolShortcutItem(
            id = "collage",
            title = "Kolase",
            subtitle = "1–9 foto fleksibel",
            icon = Icons.AutoMirrored.Filled.ViewQuilt,
            accentColor = Color(0xFF34D399),
            onClick = {
                if (existingCollageCells.isNotEmpty()) {
                    onNavigateToCollage()
                } else {
                    collageMultiPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
        ),
        ToolShortcutItem(
            id = "adjust",
            title = "Warna & Cahaya",
            subtitle = "Eksposur, nada & HSL",
            icon = Icons.Default.Tune,
            accentColor = Color(0xFFFBBF24),
            onClick = { openPickerFor(EditorTool.ADJUST) }
        ),
        ToolShortcutItem(
            id = "filter",
            title = "Filter",
            subtitle = "12 preset film & warna",
            icon = Icons.Default.Filter,
            accentColor = Color(0xFFFB923C),
            onClick = { openPickerFor(EditorTool.FILTER) }
        ),
        ToolShortcutItem(
            id = "bokeh",
            title = "Blur & Cahaya",
            subtitle = "Bokeh & arah cahaya",
            icon = Icons.Default.BlurOn,
            accentColor = Color(0xFF60A5FA),
            onClick = { openPickerFor(EditorTool.BOKEH) }
        ),
        ToolShortcutItem(
            id = "erase",
            title = "Hapus Objek",
            subtitle = "Kuas hapus gangguan",
            icon = Icons.Default.Healing,
            accentColor = Color(0xFFF87171),
            onClick = { openPickerFor(EditorTool.ERASE) }
        ),
        ToolShortcutItem(
            id = "crop",
            title = "Potong & Lurus",
            subtitle = "Rasio sosmed & rotasi",
            icon = Icons.Default.Crop,
            accentColor = Color(0xFF2DD4BF),
            onClick = { openPickerFor(EditorTool.CROP) }
        ),
        ToolShortcutItem(
            id = "passport",
            title = "Pasfoto",
            subtitle = "2×3, 3×4, 4×6 & 4R",
            icon = Icons.Default.Badge,
            accentColor = Color(0xFF818CF8),
            onClick = { openPickerFor(EditorTool.PASSPORT) }
        ),
        ToolShortcutItem(
            id = "text",
            title = "Teks",
            subtitle = "Depan & belakang subjek",
            icon = Icons.Default.Title,
            accentColor = Color(0xFFE879F9),
            onClick = { openPickerFor(EditorTool.TEXT) }
        ),
        ToolShortcutItem(
            id = "models",
            title = "Model Animasi",
            subtitle = "$installedModelsCount/$totalModelsCount terpasang",
            icon = Icons.Default.CloudDownload,
            accentColor = Color(0xFF22D3EE),
            onClick = { showModelManager = true }
        )
    )

    Scaffold(containerColor = ArArColors.CanvasBg) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Minimal Brand Header
            item(span = { GridItemSpan(2) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "ArAr Shot",
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "ArAr Shot",
                        style = MaterialTheme.typography.titleLarge,
                        color = ArArColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showModelManager = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ArArColors.Surface2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Kelola Model Animasi",
                            tint = if (installedModelsCount > 0) ArArColors.AccentCyan else ArArColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                launchCamera()
                            } else {
                                cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ArArColors.Surface2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Kamera",
                            tint = ArArColors.TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Primary Action CTA
            item(span = { GridItemSpan(2) }) {
                Column(modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) {
                    PrimaryActionButton(
                        text = "Pilih Foto",
                        icon = Icons.Default.AddPhotoAlternate,
                        onClick = { openPickerFor(EditorTool.ADJUST) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    )
                }
            }

            // Section Title
            item(span = { GridItemSpan(2) }) {
                Text(
                    text = "Alat Cepat",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArArColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }

            // Icon-Driven Tool Shortcut Grid (each tool picks its own photo)
            items(shortcuts, key = { it.id }) { item ->
                ToolShortcutCard(item = item)
            }
        }
    }

    if (showModelManager) {
        AnimationModelManagerSheet(
            state = animationModelsState,
            onDownload = { style -> viewModel.downloadAnimationStyle(context, style) },
            onCancelDownload = { viewModel.cancelAnimationDownload() },
            onDelete = { style -> viewModel.deleteAnimationStyle(context, style) },
            onDeleteAll = { viewModel.deleteAllAnimationStyles(context) },
            onDismiss = { showModelManager = false }
        )
    }
}

@Composable
private fun ToolShortcutCard(item: ToolShortcutItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ArArColors.Surface1)
            .border(1.dp, ArArColors.Border, RoundedCornerShape(16.dp))
            .clickable(onClick = item.onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(item.accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = item.accentColor,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ArArColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitle,
                fontSize = 11.sp,
                color = ArArColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
