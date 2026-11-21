package com.ararshot.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ararshot.app.R
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.model.EditorTool
import com.ararshot.app.ui.components.PrimaryActionButton
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EnhancerViewModel
import java.io.File

@Composable
fun HomeScreen(
    viewModel: EnhancerViewModel,
    onNavigateToEditor: () -> Unit,
    onNavigateToCollage: () -> Unit
) {
    val context = LocalContext.current
    val recentPhotos by viewModel.recentPhotos.collectAsState()

    val mediaPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasMediaAccess by remember {
        mutableStateOf(hasPhotoReadPermission(context))
    }

    val mediaPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMediaAccess = hasPhotoReadPermission(context)
        if (hasMediaAccess) viewModel.refreshRecentPhotos(context)
    }

    LaunchedEffect(hasMediaAccess) {
        if (hasMediaAccess) {
            viewModel.refreshRecentPhotos(context)
        }
    }

    var pendingTool by remember { mutableStateOf(EditorTool.ADJUST) }

    // Modern Android Photo Picker (requires zero storage permission!)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadFromUri(context, it, initialTool = pendingTool)
            onNavigateToEditor()
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
        } catch (e: Exception) {
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
        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(containerColor = ArArColors.CanvasBg) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Minimal Brand Header
            item(span = { GridItemSpan(3) }) {
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
            item(span = { GridItemSpan(3) }) {
                Column(modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)) {
                    PrimaryActionButton(
                        text = "Pilih Foto",
                        icon = Icons.Default.AddPhotoAlternate,
                        onClick = { openPickerFor(EditorTool.ADJUST) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    )

                    Spacer(Modifier.height(14.dp))

                    // Clean horizontal quick shortcuts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickShortcutChip("Hapus Latar", Icons.Default.ContentCut) {
                            openPickerFor(EditorTool.BACKGROUND)
                        }
                        QuickShortcutChip("Tingkatkan", Icons.Default.AutoAwesome) {
                            openPickerFor(EditorTool.ENHANCE)
                        }
                        QuickShortcutChip("Kolase", Icons.AutoMirrored.Filled.ViewQuilt) {
                            onNavigateToCollage()
                        }
                        QuickShortcutChip("Pasfoto", Icons.Default.Badge) {
                            openPickerFor(EditorTool.PASSPORT)
                        }
                    }
                }
            }

            // Recent Photos Header
            item(span = { GridItemSpan(3) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Foto Terbaru",
                        style = MaterialTheme.typography.titleSmall,
                        color = ArArColors.TextSecondary
                    )
                    if (!hasMediaAccess) {
                        Text(
                            text = "Izinkan Akses",
                            style = MaterialTheme.typography.labelLarge,
                            color = ArArColors.AccentCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(100))
                                .clickable {
                                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                                    } else {
                                        arrayOf(mediaPermission)
                                    }
                                    mediaPermLauncher.launch(perms)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (!hasMediaAccess) {
                item(span = { GridItemSpan(3) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(ArArColors.Surface1)
                            .border(1.dp, ArArColors.Border, RoundedCornerShape(16.dp))
                            .clickable { openPickerFor(EditorTool.ADJUST) }
                            .padding(vertical = 32.dp, horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = ArArColors.TextMuted,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Ketuk Pilih Foto atau izinkan akses galeri",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArArColors.TextSecondary
                            )
                        }
                    }
                }
            } else if (recentPhotos.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Belum ada foto di galeri",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArArColors.TextMuted
                        )
                    }
                }
            } else {
                items(recentPhotos, key = { it.id }) { photo ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photo.uri)
                            .size(320)
                            .crossfade(true)
                            .build(),
                        contentDescription = photo.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ArArColors.Surface2)
                            .clickable {
                                viewModel.loadFromUri(context, photo.uri, initialTool = EditorTool.ADJUST)
                                onNavigateToEditor()
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickShortcutChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ArArColors.Surface1)
            .border(1.dp, ArArColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ArArColors.TextPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ArArColors.TextPrimary
        )
    }
}

private fun hasPhotoReadPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
    }
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
