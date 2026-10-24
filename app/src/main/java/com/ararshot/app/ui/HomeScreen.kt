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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ararshot.app.R
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
                        text = "Remini-Level Face Restore & 4x AI Studio",
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
                            text = if (isModelReady) "6-Model SOTA NCNN Engine: AKTIF" else "Inisialisasi Hardware Engine...",
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

            // 1. Feature Card: REMINI FACE RESTORATION & 4X AI SUPER-RESOLUTION
            StudioFeatureCard(
                title = "Remini Face Restore & AI 4X",
                description = "Rekonstruksi wajah buram (SCRFD 5-Landmark + tekstur mikro pori kulit & iris mata) serta Super-Resolution 4x Real-ESRGAN NCNN offline.",
                icon = Icons.Default.FaceRetouchingNatural,
                accentColor = MaterialTheme.colorScheme.primary,
                tag = "POPULER",
                onClick = {
                    pendingTab = EditorTab.AI_ENHANCE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Feature Card: MAGIC ERASER / OBJECT REMOVAL
            StudioFeatureCard(
                title = "Magic Eraser (Hapus Objek AI)",
                description = "Usap kabel, orang lewat, noda, atau objek mengganggu secara presisi lalu AI PatchMatch menambalnya secara mulus.",
                icon = Icons.Default.Healing,
                accentColor = Color(0xFFFF1744),
                tag = "POPULER",
                onClick = {
                    pendingTab = EditorTab.MAGIC_ERASER
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Feature Card: AI DEPTH BOKEH & 3D RELIGHTING
            StudioFeatureCard(
                title = "AI Depth Anything Bokeh & 3D Relighting",
                description = "Bokeh optik lensa f/1.4 berbasis estimasi Depth Anything 3D dan simulasi tata cahaya studio 3D Blinn-Phong.",
                icon = Icons.Default.CameraEnhance,
                accentColor = Color(0xFF00E5FF),
                tag = "POPULER",
                onClick = {
                    pendingTab = EditorTab.DEPTH_BOKEH_3D
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Feature Card: 1-TAP AI CUTOUT & STUDIO PRODUCT SHADOW
            StudioFeatureCard(
                title = "AI Cutout, Hair Matting & Teks Belakang",
                description = "Pemisahan helai rambut sub-pixel (MODNet Guided Filter), bayangan produk 3D, stiker PNG, dan teks di belakang subjek.",
                icon = Icons.Default.Layers,
                accentColor = Color(0xFFD81B60),
                tag = "POPULER",
                onClick = {
                    pendingTab = EditorTab.BG_REMOVE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Feature Card: COLORIZE OLD B&W PHOTOS
            StudioFeatureCard(
                title = "Colorize Foto Hitam-Putih Jadul (CIE LAB)",
                description = "Rekonstruksi warna alami foto lawas (nada kulit, daun hijau, langit biru) tanpa mengurangi detail ketajaman luminansi.",
                icon = Icons.Default.Gradient,
                accentColor = Color(0xFFFF9100),
                tag = "STUDIO",
                onClick = {
                    pendingTab = EditorTab.COLORIZE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Feature Card: AI ANIME & GHIBLI STYLE
            StudioFeatureCard(
                title = "AnimeGANv2 & Ghibli Art Style Transfer",
                description = "Ubah foto potret atau pemandangan menjadi karya seni anime Jepang dengan neural transfer AnimeGANv2 NCNN.",
                icon = Icons.Default.AutoAwesome,
                accentColor = Color(0xFFE040FB),
                tag = "ARTISTIC",
                onClick = {
                    pendingTab = EditorTab.ANIME_STYLE
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Feature Card: AI PASSPORT / ID PHOTO MAKER & 4R SHEET
            StudioFeatureCard(
                title = "Pasfoto Resmi (SCRFD Biometrik) & Cetak 4R",
                description = "Penyesuaian posisi mata & kepala standar ICAO (2x3, 3x4, 4x6), penggantian latar Merah/Biru, dan tata letak lembar cetak 4R.",
                icon = Icons.Default.ContactPage,
                accentColor = Color(0xFF00C853),
                tag = "PRO",
                onClick = {
                    pendingTab = EditorTab.PASSPORT_PHOTO
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 8. Feature Card: VINTAGE CCD & RED HALATION
            StudioFeatureCard(
                title = "Vintage Digicam CCD & Red Halation",
                description = "Nuansa kamera saku klasik era 2000-an: pendaran merah CineStill 800T pada cahaya malam, butiran analog, dan stempel tanggal LCD.",
                icon = Icons.Default.FilterVintage,
                accentColor = Color(0xFFFF6D00),
                tag = "POPULER",
                onClick = {
                    pendingTab = EditorTab.VINTAGE_CCD
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 9. Feature Card: TEXT & TYPOGRAPHY STYLER
            StudioFeatureCard(
                title = "Studio Tipografi & Teks Layer",
                description = "Tambahkan teks dengan font tipografi pilihan, efek neon Cyberpunk, bayangan 3D realistis, dan outline profesional.",
                icon = Icons.Default.Title,
                accentColor = Color(0xFF1E88E5),
                tag = "TYPO",
                onClick = {
                    pendingTab = EditorTab.TEXT_TYPOGRAPHY
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 10. Feature Card: PRO COLOR GRADING & FILTERS
            StudioFeatureCard(
                title = "Pro Color Grading & Tone Curve",
                description = "Koreksi pencahayaan tingkat lanjut: Exposure, Contrast, Saturation, Temperature, dan Vignette dengan respon real-time.",
                icon = Icons.Default.Tune,
                accentColor = Color(0xFF00897B),
                tag = "PRO",
                onClick = {
                    pendingTab = EditorTab.COLOR_GRADING
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 11. Feature Card: CROP & TRANSFORM
            StudioFeatureCard(
                title = "Crop & Transform Studio Presisi",
                description = "Pemotongan rasio standar (1:1, 4:5, 16:9), rotasi 90 derajat bebas distorsi, serta pembalikan horizontal dan vertikal.",
                icon = Icons.Default.CropRotate,
                accentColor = Color(0xFFE65100),
                tag = "TOOL",
                onClick = {
                    pendingTab = EditorTab.CROP_TRANSFORM
                    galleryLauncher.launch("image/*")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 12. Feature Card: COLLAGE STUDIO GRID
            StudioFeatureCard(
                title = "Collage Studio Grid & Border",
                description = "Kombinasi 2 hingga 4 foto menjadi kolase proporsional (aspect-fill) dengan sudut melengkung dan jarak dinamis.",
                icon = Icons.AutoMirrored.Filled.ViewQuilt,
                accentColor = Color(0xFF6A1B9A),
                tag = "STUDIO",
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
    tag: String? = null,
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
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.14f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (tag != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (tag == "POPULER") Color(0xFFFF5252).copy(alpha = 0.15f)
                                    else accentColor.copy(alpha = 0.12f)
                                )
                                .border(
                                    0.8.dp,
                                    if (tag == "POPULER") Color(0xFFFF5252).copy(alpha = 0.6f)
                                    else accentColor.copy(alpha = 0.4f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (tag == "POPULER") Color(0xFFFF5252) else accentColor,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
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
