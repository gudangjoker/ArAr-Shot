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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ararshot.app.engine.ImageUtils
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
    val scrollState = rememberScrollState()

    val collagePhotos by viewModel.collageBitmaps.collectAsState()

    var spacingDp by remember { mutableFloatStateOf(8f) }
    var cornerRadiusDp by remember { mutableFloatStateOf(8f) }
    var templateIndex by remember { mutableIntStateOf(0) }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 1200)
                viewModel.addCollagePhoto(bmp)
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memuat foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collage Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (collagePhotos.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearCollage() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Reset")
                        }
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // COLLAGE CANVAS PREVIEW (Square 1:1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(spacingDp.dp),
                contentAlignment = Alignment.Center
            ) {
                if (collagePhotos.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ViewQuilt,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Belum Ada Foto Dipilih",
                            color = Color.LightGray,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tambahkan 2 s.d. 4 foto untuk membuat kolase.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    CollageLayoutRenderer(
                        photos = collagePhotos,
                        spacingDp = spacingDp,
                        cornerRadiusDp = cornerRadiusDp,
                        templateIndex = templateIndex
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // PHOTO SLOTS & ADD BUTTON
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Foto Kolase (${collagePhotos.size}/4)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                if (collagePhotos.size < 4) {
                    Button(
                        onClick = { pickPhotoLauncher.launch("image/*") },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tambah Foto", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Thumbnails row
            if (collagePhotos.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    collagePhotos.forEachIndexed { index, bmp ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Slot $index",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    for (i in collagePhotos.size until 4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable { pickPhotoLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // SLIDERS: SPACING & CORNERS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Pengaturan Tampilan Kolase", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Jarak Antarfoto (Margin):", fontSize = 12.sp)
                        Text("${spacingDp.toInt()} dp", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = spacingDp, onValueChange = { spacingDp = it }, valueRange = 0f..24f)

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kelengkungan Sudut (Corner):", fontSize = 12.sp)
                        Text("${cornerRadiusDp.toInt()} dp", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = cornerRadiusDp, onValueChange = { cornerRadiusDp = it }, valueRange = 0f..24f)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // SAVE COLLAGE BUTTON
            Button(
                onClick = {
                    if (collagePhotos.isEmpty()) {
                        Toast.makeText(context, "Tambahkan foto terlebih dahulu!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        val collageBmp = renderCollageToBitmap(collagePhotos, spacingDp, cornerRadiusDp)
                        val saved = ImageUtils.saveBitmapToGallery(context, collageBmp, "ArArShot_Collage_${System.currentTimeMillis()}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (saved) "Kolase berhasil disimpan ke Galeri (Pictures/ArArShot)!" else "Gagal menyimpan kolase.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = collagePhotos.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Simpan Kolase ke Galeri", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CollageLayoutRenderer(
    photos: List<Bitmap>,
    spacingDp: Float,
    cornerRadiusDp: Float,
    templateIndex: Int
) {
    val cornerShape = RoundedCornerShape(cornerRadiusDp.dp)

    when (photos.size) {
        1 -> {
            Image(
                bitmap = photos[0].asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cornerShape),
                contentScale = ContentScale.Crop
            )
        }
        2 -> {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)
            ) {
                Image(
                    bitmap = photos[0].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(cornerShape),
                    contentScale = ContentScale.Crop
                )
                Image(
                    bitmap = photos[1].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(cornerShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
        3 -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacingDp.dp)
            ) {
                Image(
                    bitmap = photos[0].asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(cornerShape),
                    contentScale = ContentScale.Crop
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)
                ) {
                    Image(
                        bitmap = photos[1].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                    Image(
                        bitmap = photos[2].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacingDp.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)
                ) {
                    Image(
                        bitmap = photos[0].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                    Image(
                        bitmap = photos[1].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacingDp.dp)
                ) {
                    Image(
                        bitmap = photos[2].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                    Image(
                        bitmap = photos[3].asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * Draws a bitmap center-cropped (ContentScale.Crop) inside dstRect with rounded corners.
 */
private fun drawBitmapCenterCropRounded(
    canvas: Canvas,
    bitmap: Bitmap,
    dstRect: RectF,
    cornerRadiusPx: Float,
    paint: Paint
) {
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
    val srcRight = (srcLeft + cropW.toInt()).coerceAtMost(bitmap.width)
    val srcBottom = (srcTop + cropH.toInt()).coerceAtMost(bitmap.height)
    val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

    val saveCount = canvas.save()
    if (cornerRadiusPx > 0.5f) {
        val clipPath = Path().apply {
            addRoundRect(dstRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
    }
    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    canvas.restoreToCount(saveCount)
}

/**
 * Renders the collage layout directly onto a 1200x1200 high-res bitmap for gallery saving,
 * preserving both Center-Crop aspect ratio and rounded corners (WYSIWYG).
 */
fun renderCollageToBitmap(photos: List<Bitmap>, spacingDp: Float, cornerRadiusDp: Float): Bitmap {
    val size = 1200
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(android.graphics.Color.rgb(30, 30, 30))

    val spacingPx = spacingDp * 3f
    val cornerRadiusPx = cornerRadiusDp * 3f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    when (photos.size) {
        1 -> {
            val dest = RectF(spacingPx, spacingPx, size - spacingPx, size - spacingPx)
            drawBitmapCenterCropRounded(canvas, photos[0], dest, cornerRadiusPx, paint)
        }
        2 -> {
            val halfW = (size - spacingPx * 3) / 2f
            val h = size - spacingPx * 2

            val rect1 = RectF(spacingPx, spacingPx, spacingPx + halfW, spacingPx + h)
            val rect2 = RectF(spacingPx * 2 + halfW, spacingPx, spacingPx * 2 + halfW * 2, spacingPx + h)

            drawBitmapCenterCropRounded(canvas, photos[0], rect1, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], rect2, cornerRadiusPx, paint)
        }
        3 -> {
            val halfH = (size - spacingPx * 3) / 2f
            val rectTop = RectF(spacingPx, spacingPx, size - spacingPx, spacingPx + halfH)

            val halfW = (size - spacingPx * 3) / 2f
            val rectBtm1 = RectF(spacingPx, spacingPx * 2 + halfH, spacingPx + halfW, size - spacingPx)
            val rectBtm2 = RectF(spacingPx * 2 + halfW, spacingPx * 2 + halfH, size - spacingPx, size - spacingPx)

            drawBitmapCenterCropRounded(canvas, photos[0], rectTop, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], rectBtm1, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[2], rectBtm2, cornerRadiusPx, paint)
        }
        else -> {
            val halfW = (size - spacingPx * 3) / 2f
            val halfH = (size - spacingPx * 3) / 2f

            val r1 = RectF(spacingPx, spacingPx, spacingPx + halfW, spacingPx + halfH)
            val r2 = RectF(spacingPx * 2 + halfW, spacingPx, size - spacingPx, spacingPx + halfH)
            val r3 = RectF(spacingPx, spacingPx * 2 + halfH, spacingPx + halfW, size - spacingPx)
            val r4 = RectF(spacingPx * 2 + halfW, spacingPx * 2 + halfH, size - spacingPx, size - spacingPx)

            drawBitmapCenterCropRounded(canvas, photos[0], r1, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[1], r2, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[2], r3, cornerRadiusPx, paint)
            drawBitmapCenterCropRounded(canvas, photos[3], r4, cornerRadiusPx, paint)
        }
    }

    return output
}
