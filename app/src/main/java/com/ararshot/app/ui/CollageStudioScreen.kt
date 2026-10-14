package com.ararshot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var templateIndex by remember { mutableIntStateOf(0) } // 0: Grid, 1: Vertical split

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, it, maxDim = 800)
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
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
                            imageVector = Icons.Default.GridOn,
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
                    // Empty slots placeholder
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
            // 4 Photos (2x2 Grid)
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
 * Renders the collage layout directly onto a 1080x1080 high-res bitmap for gallery saving.
 */
fun renderCollageToBitmap(photos: List<Bitmap>, spacingDp: Float, cornerRadiusDp: Float): Bitmap {
    val size = 1200
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(android.graphics.Color.rgb(30, 30, 30))

    val spacingPx = (spacingDp * 3f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    when (photos.size) {
        1 -> {
            val dest = Rect(spacingPx.toInt(), spacingPx.toInt(), (size - spacingPx).toInt(), (size - spacingPx).toInt())
            canvas.drawBitmap(photos[0], null, dest, paint)
        }
        2 -> {
            val halfW = (size - spacingPx * 3) / 2f
            val h = size - spacingPx * 2

            val rect1 = Rect(spacingPx.toInt(), spacingPx.toInt(), (spacingPx + halfW).toInt(), (spacingPx + h).toInt())
            val rect2 = Rect((spacingPx * 2 + halfW).toInt(), spacingPx.toInt(), (spacingPx * 2 + halfW * 2).toInt(), (spacingPx + h).toInt())

            canvas.drawBitmap(photos[0], null, rect1, paint)
            canvas.drawBitmap(photos[1], null, rect2, paint)
        }
        3 -> {
            val halfH = (size - spacingPx * 3) / 2f
            val rectTop = Rect(spacingPx.toInt(), spacingPx.toInt(), (size - spacingPx).toInt(), (spacingPx + halfH).toInt())

            val halfW = (size - spacingPx * 3) / 2f
            val rectBtm1 = Rect(spacingPx.toInt(), (spacingPx * 2 + halfH).toInt(), (spacingPx + halfW).toInt(), (size - spacingPx).toInt())
            val rectBtm2 = Rect((spacingPx * 2 + halfW).toInt(), (spacingPx * 2 + halfH).toInt(), (size - spacingPx).toInt(), (size - spacingPx).toInt())

            canvas.drawBitmap(photos[0], null, rectTop, paint)
            canvas.drawBitmap(photos[1], null, rectBtm1, paint)
            canvas.drawBitmap(photos[2], null, rectBtm2, paint)
        }
        else -> {
            val halfW = (size - spacingPx * 3) / 2f
            val halfH = (size - spacingPx * 3) / 2f

            val r1 = Rect(spacingPx.toInt(), spacingPx.toInt(), (spacingPx + halfW).toInt(), (spacingPx + halfH).toInt())
            val r2 = Rect((spacingPx * 2 + halfW).toInt(), spacingPx.toInt(), (size - spacingPx).toInt(), (spacingPx + halfH).toInt())
            val r3 = Rect(spacingPx.toInt(), (spacingPx * 2 + halfH).toInt(), (spacingPx + halfW).toInt(), (size - spacingPx).toInt())
            val r4 = Rect((spacingPx * 2 + halfW).toInt(), (spacingPx * 2 + halfH).toInt(), (size - spacingPx).toInt(), (size - spacingPx).toInt())

            canvas.drawBitmap(photos[0], null, r1, paint)
            canvas.drawBitmap(photos[1], null, r2, paint)
            canvas.drawBitmap(photos[2], null, r3, paint)
            canvas.drawBitmap(photos[3], null, r4, paint)
        }
    }

    return output
}
