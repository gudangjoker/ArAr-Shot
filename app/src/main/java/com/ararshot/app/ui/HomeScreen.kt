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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ararshot.app.R
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.viewmodel.EditorTab
import com.ararshot.app.viewmodel.EnhancerViewModel

@Composable
fun HomeScreen(
    viewModel: EnhancerViewModel,
    isModelReady: Boolean,
    onNavigateToEditor: () -> Unit,
    onNavigateToCollage: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Permissions
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

    // Launchers for Image Selection with Target Tab
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
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "ArAr Shot Pro",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Complete Studio & 4x AI Super-Resolution",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Engine & Hardware Status Banner
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
                            text = if (isModelReady) "Real-ESRGAN Vulkan Engine: AKTIF" else "Inisialisasi Hardware Engine...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (!permissionsGranted) {
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermissions) },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Izin", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Pilih Fitur Studio",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Feature Card: 4X AI SUPER-RESOLUTION & DENOISE
            StudioFeatureCard(
                title = "🚀 AI Super-Resolution 4X",
                description = "Tingkatkan ketajaman foto hingga 4x lipat menggunakan model AI Real-ESRGAN NCNN Vulkan offline.",
                icon = Icons.Default.AutoAwesome,
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = {
                    pendingTab = EditorTab.AI_ENHANCE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Feature Card: MAGIC ERASER / OBJECT REMOVAL
            StudioFeatureCard(
                title = "✨ Magic Eraser (Hapus Objek Pengganggu)",
                description = "Usap kabel listrik, orang lewat, jerawat, atau objek yang tidak diinginkan lalu AI menambalnya secara mulus.",
                icon = Icons.Default.Brush,
                accentColor = Color(0xFFFF1744),
                onClick = {
                    pendingTab = EditorTab.MAGIC_ERASER
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Feature Card: AI DEPTH BOKEH & 3D RELIGHTING
            StudioFeatureCard(
                title = "📸 AI Depth Bokeh & 3D Relighting",
                description = "Bokeh optik lensa DSLR f/1.4 tajam pada subjek + geser tata cahaya studio 3D Cyberpunk / Golden Hour.",
                icon = Icons.Default.BlurOn,
                accentColor = Color(0xFF00E5FF),
                onClick = {
                    pendingTab = EditorTab.DEPTH_BOKEH_3D
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Feature Card: 1-TAP AI CUTOUT & STUDIO PRODUCT SHADOW
            StudioFeatureCard(
                title = "✂️ AI Cutout, Drop Shadow & Teks Belakang Orang",
                description = "Pisahkan subjek, buat bayangan produk 3D realistis, outline stiker putih, atau selipkan teks keren di belakang orang.",
                icon = Icons.Default.ContentCut,
                accentColor = Color(0xFFD81B60),
                onClick = {
                    pendingTab = EditorTab.BG_REMOVE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Feature Card: COLORIZE OLD B&W PHOTOS
            StudioFeatureCard(
                title = "🎨 Warnai Foto Hitam-Putih Jadul (Colorize)",
                description = "Ubah foto kakek/nenek dan foto sejarah hitam-putih menjadi berwarna alami dengan model warna AI realistis.",
                icon = Icons.Default.ColorLens,
                accentColor = Color(0xFFFF9100),
                onClick = {
                    pendingTab = EditorTab.COLORIZE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Feature Card: AI ANIME & GHIBLI STYLE
            StudioFeatureCard(
                title = "🌸 AI Anime / Ghibli Style Transfer",
                description = "Ubah foto portrait atau pemandangan menjadi gaya lukisan anime Jepang Makoto Shinkai & Studio Ghibli.",
                icon = Icons.Default.Face,
                accentColor = Color(0xFFE040FB),
                onClick = {
                    pendingTab = EditorTab.ANIME_STYLE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Feature Card: AI PASSPORT / ID PHOTO MAKER & 4R SHEET
            StudioFeatureCard(
                title = "🪪 Pasfoto Resmi & Lembar Cetak 4R",
                description = "Potong rasio pasfoto resmi (2x3, 3x4, 4x6), ganti background Merah/Biru, dan susun ke lembar cetak 4R siap print.",
                icon = Icons.Default.Badge,
                accentColor = Color(0xFF00C853),
                onClick = {
                    pendingTab = EditorTab.PASSPORT_PHOTO
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 8. Feature Card: VINTAGE CCD & RED HALATION
            StudioFeatureCard(
                title = "📸 Vintage Digicam CCD & Red Halation",
                description = "Tren kamera saku 2000-an: pendaran merah lembut pada lampu malam, analog grain, dan cap tanggal LCD '98 10 14.",
                icon = Icons.Default.CameraAlt,
                accentColor = Color(0xFFFF6D00),
                onClick = {
                    pendingTab = EditorTab.VINTAGE_CCD
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 9. Feature Card: TEXT & TYPOGRAPHY STYLER
            StudioFeatureCard(
                title = "✍️ Studio Teks & Tipografi",
                description = "Tambahkan teks dengan font premium, efek Cyberpunk Neon, 3D Shadow, Outline, dan Instagram Badge box.",
                icon = Icons.Default.TextFields,
                accentColor = Color(0xFF1E88E5),
                onClick = {
                    pendingTab = EditorTab.TEXT_TYPOGRAPHY
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 10. Feature Card: PRO COLOR GRADING & FILTERS
            StudioFeatureCard(
                title = "🎨 Pro Color Studio & Filter",
                description = "Pengaturan presisi Exposure, Contrast, Saturation, Temperature, dan Vignette secara real-time.",
                icon = Icons.Default.Palette,
                accentColor = Color(0xFF00897B),
                onClick = {
                    pendingTab = EditorTab.COLOR_GRADING
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 11. Feature Card: CROP & TRANSFORM
            StudioFeatureCard(
                title = "📐 Crop & Transform Studio",
                description = "Potong foto ke rasio 1:1, 4:5, 16:9, rotasi presisi 90 derajat, dan flip horizontal/vertikal.",
                icon = Icons.Default.Crop,
                accentColor = Color(0xFFE65100),
                onClick = {
                    pendingTab = EditorTab.CROP_TRANSFORM
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 12. Feature Card: COLLAGE STUDIO GRID
            StudioFeatureCard(
                title = "🖼️ Collage Studio Grid",
                description = "Gabungkan 2 hingga 4 foto menjadi kolase artistik dengan template grid dan margin dinamis.",
                icon = Icons.Default.GridOn,
                accentColor = Color(0xFF6A1B9A),
                onClick = {
                    onNavigateToCollage()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Actions: Camera & Gallery Direct
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pendingTab = EditorTab.AI_ENHANCE
                        cameraLauncher.launch(null)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Foto Kamera", fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        pendingTab = EditorTab.AI_ENHANCE
                        galleryLauncher.launch("image/*")
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Buka Galeri", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StudioFeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
