package com.ararshot.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraEnhance
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.ararshot.app.ui.components.SectionHeader
import com.ararshot.app.ui.components.TagChip
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EditorTab
import com.ararshot.app.viewmodel.EnhancerViewModel

private data class ToolItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color,
    val tag: String? = null,
    val tab: EditorTab? = null // null => Collage
)

private data class ToolGroup(val title: String, val subtitle: String, val items: List<ToolItem>)

private val toolGroups = listOf(
    ToolGroup(
        title = "Perbaiki & Tingkatkan",
        subtitle = "Ubah foto buram menjadi tajam dan jernih",
        items = listOf(
            ToolItem(
                "Restorasi & 4X",
                "Pulihkan wajah buram dan tingkatkan resolusi 4X.",
                Icons.Default.FaceRetouchingNatural, ArArColors.Violet, "POPULER", EditorTab.AI_ENHANCE
            ),
            ToolItem(
                "Hapus Objek",
                "Hilangkan orang lewat, kabel, atau noda pengganggu.",
                Icons.Default.Healing, ArArColors.Coral, "POPULER", EditorTab.MAGIC_ERASER
            ),
            ToolItem(
                "Bokeh & 3D",
                "Latar blur ala lensa f/1.4 dan pencahayaan studio.",
                Icons.Default.CameraEnhance, ArArColors.Cyan, null, EditorTab.DEPTH_BOKEH_3D
            ),
            ToolItem(
                "Pewarnaan Lawas",
                "Beri warna alami pada foto hitam-putih kenangan.",
                Icons.Default.Gradient, ArArColors.Amber, null, EditorTab.COLORIZE
            )
        )
    ),
    ToolGroup(
        title = "Kreatif & Gaya",
        subtitle = "Gaya seni, stiker, dan sentuhan retro",
        items = listOf(
            ToolItem(
                "Hapus Latar",
                "Pisahkan subjek hingga helai rambut, buat PNG transparan.",
                Icons.Default.Layers, ArArColors.Pink, "POPULER", EditorTab.BG_REMOVE
            ),
            ToolItem(
                "Gaya Anime",
                "Ubah potret menjadi ilustrasi anime artistik.",
                Icons.Default.AutoAwesome, Color(0xFFC084FC), null, EditorTab.ANIME_STYLE
            ),
            ToolItem(
                "Kamera Lawas",
                "Nuansa kamera saku 2000-an lengkap dengan cap tanggal.",
                Icons.Default.FilterVintage, Color(0xFFFB923C), null, EditorTab.VINTAGE_CCD
            ),
            ToolItem(
                "Teks & Font",
                "Tambahkan tulisan neon, bayangan 3D, dan garis tepi.",
                Icons.Default.Title, ArArColors.Sky, null, EditorTab.TEXT_TYPOGRAPHY
            )
        )
    ),
    ToolGroup(
        title = "Dokumen & Tata Letak",
        subtitle = "Pasfoto resmi, susunan kolase, dan pemotongan",
        items = listOf(
            ToolItem(
                "Pasfoto Resmi",
                "Ukuran 2x3 / 3x4 / 4x6, latar merah-biru, siap cetak 4R.",
                Icons.Default.ContactPage, ArArColors.Mint, "PRO", EditorTab.PASSPORT_PHOTO
            ),
            ToolItem(
                "Kolase Foto",
                "Gabungkan 2 hingga 4 foto dengan tata letak rapi.",
                Icons.AutoMirrored.Filled.ViewQuilt, Color(0xFFA78BFA), null, null
            ),
            ToolItem(
                "Koreksi Warna",
                "Atur pencahayaan, kontras, suhu warna, dan vinyet.",
                Icons.Default.Tune, Color(0xFF2DD4BF), "PRO", EditorTab.COLOR_GRADING
            ),
            ToolItem(
                "Potong & Putar",
                "Pilihan rasio 1:1, 4:5, 16:9, rotasi, dan balik arah.",
                Icons.Default.CropRotate, Color(0xFFFB7185), null, EditorTab.CROP_TRANSFORM
            )
        )
    )
)

@Composable
fun HomeScreen(
    viewModel: EnhancerViewModel,
    isModelReady: Boolean,
    onNavigateToEditor: () -> Unit,
    onNavigateToCollage: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Permissions (100% Privacy-First: Camera + Image Media only, zero audio/network permissions)
    val requiredPermissions = remember {
        val list = mutableListOf(Manifest.permission.CAMERA)
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

    var pendingTab by remember { mutableStateOf(EditorTab.AI_ENHANCE) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadFromUri(context, it)
            viewModel.setCurrentTab(pendingTab)
            onNavigateToEditor()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            viewModel.setSourceBitmap(it)
            viewModel.setCurrentTab(pendingTab)
            onNavigateToEditor()
        }
    }

    val openGallery = {
        pendingTab = EditorTab.AI_ENHANCE
        galleryLauncher.launch("image/*")
    }
    val openCamera = {
        pendingTab = EditorTab.AI_ENHANCE
        cameraLauncher.launch(null)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            HomeHeader(isModelReady = isModelReady)

            Spacer(Modifier.height(20.dp))
            HeroCard(onGallery = openGallery, onCamera = openCamera)

            if (!permissionsGranted) {
                Spacer(Modifier.height(14.dp))
                PermissionBanner(onGrant = { permissionLauncher.launch(requiredPermissions) })
            }

            toolGroups.forEach { group ->
                Spacer(Modifier.height(28.dp))
                SectionHeader(title = group.title, subtitle = group.subtitle)
                Spacer(Modifier.height(12.dp))
                ToolGrid(
                    items = group.items,
                    onItemClick = { item ->
                        val tab = item.tab
                        if (tab == null) {
                            onNavigateToCollage()
                        } else {
                            pendingTab = tab
                            galleryLauncher.launch("image/*")
                        }
                    }
                )
            }

            Spacer(Modifier.height(28.dp))
            PrivacyFooter()
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HomeHeader(isModelReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "Logo ArAr Shot",
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ArAr Shot",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Studio foto AI, 100% offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusPill(ready = isModelReady)
    }
}

@Composable
private fun StatusPill(ready: Boolean) {
    val dot = if (ready) ArArColors.Mint else ArArColors.Amber
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (ready) "Siap" else "Menyiapkan",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HeroCard(onGallery: () -> Unit, onCamera: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(ArArColors.VioletDeep, ArArColors.Violet, Color(0xFF0EA5C6))
                )
            )
            .padding(22.dp)
    ) {
        // soft decorative glow
        Box(
            modifier = Modifier
                .size(150.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        )
        Column {
            Text(
                text = "Foto buram?\nJadikan jernih.",
                color = Color.White,
                fontSize = 26.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pulihkan wajah, perbesar 4x, dan hapus objek langsung di ponselmu tanpa internet.",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .clickable(onClick = onGallery),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Collections,
                        contentDescription = null,
                        tint = ArArColors.VioletDeep,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Pilih Foto",
                        color = ArArColors.VioletDeep,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onCamera),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Kamera",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArArColors.Amber.copy(alpha = 0.12f))
            .border(1.dp, ArArColors.Amber.copy(alpha = 0.4f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Izinkan akses kamera & galeri",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Hanya dipakai untuk memilih dan menyimpan foto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(ArArColors.Amber)
                .clickable(onClick = onGrant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Izinkan", color = Color(0xFF3B2A00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToolGrid(items: List<ToolItem>, onItemClick: (ToolItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { item ->
                    ToolTile(
                        item = item,
                        onClick = { onItemClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTile(item: ToolItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .height(158.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(item.accent.copy(alpha = 0.30f), item.accent.copy(alpha = 0.10f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (item.tag != null) {
                TagChip(
                    text = item.tag,
                    color = if (item.tag == "POPULER") ArArColors.Coral else item.accent
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrivacyFooter() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Semua diproses di perangkat. Tanpa internet, tanpa telemetri.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
