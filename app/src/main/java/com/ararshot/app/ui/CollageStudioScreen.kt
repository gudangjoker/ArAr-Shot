package com.ararshot.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.ui.components.PrecisionSlider
import com.ararshot.app.ui.components.PrimaryActionButton
import com.ararshot.app.ui.theme.ArArColors
import com.ararshot.app.viewmodel.EnhancerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageStudioScreen(
    viewModel: EnhancerViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val collagePhotos by viewModel.collageBitmaps.collectAsState()

    var spacingDp by remember { mutableFloatStateOf(8f) }
    var cornerRadiusDp by remember { mutableFloatStateOf(10f) }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 1200)
                viewModel.addCollagePhoto(bmp)
            } catch (_: Exception) {
                Toast.makeText(context, "Gagal membuka foto", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kolase", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (collagePhotos.isNotEmpty()) {
                        Text(
                            text = "Hapus Semua",
                            style = MaterialTheme.typography.labelLarge,
                            color = ArArColors.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(100))
                                .clickable { viewModel.clearCollage() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArArColors.CanvasBg)
            )
        },
        containerColor = ArArColors.CanvasBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ArArColors.Surface1)
                    .border(1.dp, ArArColors.Border, RoundedCornerShape(18.dp))
                    .padding(spacingDp.dp),
                contentAlignment = Alignment.Center
            ) {
                if (collagePhotos.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ViewQuilt,
                            contentDescription = null,
                            tint = ArArColors.TextMuted,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Pilih 2 sampai 4 foto", style = MaterialTheme.typography.bodyMedium, color = ArArColors.TextSecondary)
                    }
                } else {
                    CollageLayoutRenderer(
                        photos = collagePhotos,
                        spacingDp = spacingDp,
                        cornerRadiusDp = cornerRadiusDp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                collagePhotos.forEachIndexed { index, bmp ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, ArArColors.Border, RoundedCornerShape(10.dp))
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { viewModel.removeCollagePhotoAt(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Hapus", tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
                for (i in collagePhotos.size until 4) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ArArColors.Surface2)
                            .border(1.dp, ArArColors.Border, RoundedCornerShape(10.dp))
                            .clickable {
                                pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Tambah", tint = ArArColors.TextMuted, modifier = Modifier.size(22.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ArArColors.Surface1)
                    .border(1.dp, ArArColors.Border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PrecisionSlider(
                    label = "Jarak",
                    value = spacingDp,
                    valueRange = 0f..24f,
                    defaultValue = 8f,
                    valueFormatter = { "${it.toInt()} dp" },
                    onValueChange = { spacingDp = it }
                )
                PrecisionSlider(
                    label = "Sudut",
                    value = cornerRadiusDp,
                    valueRange = 0f..24f,
                    defaultValue = 10f,
                    valueFormatter = { "${it.toInt()} dp" },
                    onValueChange = { cornerRadiusDp = it }
                )
            }

            Spacer(Modifier.height(18.dp))

            PrimaryActionButton(
                text = "Simpan Kolase",
                icon = Icons.Default.Download,
                enabled = collagePhotos.isNotEmpty(),
                onClick = {
                    coroutineScope.launch(Dispatchers.Default) {
                        val bmp = renderCollageToBitmap(collagePhotos, spacingDp, cornerRadiusDp)
                        val ok = ImageUtils.saveBitmapToGallery(context, bmp, "ArArShot_Collage_${System.currentTimeMillis()}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) "Kolase tersimpan di Galeri" else "Gagal menyimpan kolase",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )
        }
    }
}

@Composable
fun CollageLayoutRenderer(
    photos: List<Bitmap>,
    spacingDp: Float,
    cornerRadiusDp: Float
) {
    val cornerShape = RoundedCornerShape(cornerRadiusDp.dp)
    when (photos.size) {
        1 -> {
            Image(
                bitmap = photos[0].asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(cornerShape),
                contentScale = ContentScale.Crop
            )
        }
        2 -> {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                photos.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        3 -> {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                Image(
                    bitmap = photos[0].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(cornerShape),
                    contentScale = ContentScale.Crop
                )
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                    Image(bitmap = photos[1].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                    Image(bitmap = photos[2].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                }
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                    Image(bitmap = photos[0].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                    Image(bitmap = photos[1].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)) {
                    Image(bitmap = photos[2].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                    Image(bitmap = photos[3].asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

private fun drawBitmapCenterCropRounded(canvas: Canvas, bitmap: Bitmap, dstRect: RectF, cornerRadiusPx: Float, paint: Paint) {
    val dstW = dstRect.width()
    val dstH = dstRect.height()
    if (dstW <= 0f || dstH <= 0f) return

    val srcW = bitmap.width.toFloat()
    val srcH = bitmap.height.toFloat()
    val dstAspect = dstW / dstH
    val srcAspect = srcW / srcH

    val cropW: Float
    val cropH: Float
    if (srcAspect > dstAspect) {
        cropH = srcH
        cropW = srcH * dstAspect
    } else {
        cropW = srcW
        cropH = srcW / dstAspect
    }
    val srcLeft = ((srcW - cropW) * 0.5f).toInt().coerceAtLeast(0)
    val srcTop = ((srcH - cropH) * 0.5f).toInt().coerceAtLeast(0)
    val srcRect = Rect(srcLeft, srcTop, (srcLeft + cropW.toInt()).coerceAtMost(bitmap.width), (srcTop + cropH.toInt()).coerceAtMost(bitmap.height))

    val saveCount = canvas.save()
    if (cornerRadiusPx > 0.5f) {
        val clipPath = Path().apply { addRoundRect(dstRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW) }
        canvas.clipPath(clipPath)
    }
    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    canvas.restoreToCount(saveCount)
}

fun renderCollageToBitmap(photos: List<Bitmap>, spacingDp: Float, cornerRadiusDp: Float): Bitmap {
    val size = 1200
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(android.graphics.Color.rgb(17, 20, 28))

    val spacingPx = spacingDp * 3f
    val cornerRadiusPx = cornerRadiusDp * 3f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    when (photos.size) {
        1 -> {
            drawBitmapCenterCropRounded(canvas, photos[0], RectF(spacingPx, spacingPx, size - spacingPx, size - spacingPx), cornerRadiusPx, paint)
        }
        2 -> {
            val halfW = (size - spacingPx * 3) / 2f
            val h = size - spacingPx * 2
            drawBitmapCenterCropRounded(canvas, photos[0], RectF(spacingPx, spacingPx, spacingPx + halfW, spacingPx + h), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], RectF(spacingPx * 2 + halfW, spacingPx, spacingPx * 2 + halfW * 2, spacingPx + h), cornerRadiusPx, paint)
        }
        3 -> {
            val halfH = (size - spacingPx * 3) / 2f
            val halfW = (size - spacingPx * 3) / 2f
            drawBitmapCenterCropRounded(canvas, photos[0], RectF(spacingPx, spacingPx, size - spacingPx, spacingPx + halfH), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], RectF(spacingPx, spacingPx * 2 + halfH, spacingPx + halfW, size - spacingPx), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[2], RectF(spacingPx * 2 + halfW, spacingPx * 2 + halfH, size - spacingPx, size - spacingPx), cornerRadiusPx, paint)
        }
        else -> {
            val halfW = (size - spacingPx * 3) / 2f
            val halfH = (size - spacingPx * 3) / 2f
            drawBitmapCenterCropRounded(canvas, photos[0], RectF(spacingPx, spacingPx, spacingPx + halfW, spacingPx + halfH), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], RectF(spacingPx * 2 + halfW, spacingPx, size - spacingPx, spacingPx + halfH), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[2], RectF(spacingPx, spacingPx * 2 + halfH, spacingPx + halfW, size - spacingPx), cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[3], RectF(spacingPx * 2 + halfW, spacingPx * 2 + halfH, size - spacingPx, size - spacingPx), cornerRadiusPx, paint)
        }
    }
    return output
}
