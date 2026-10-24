package com.ararshot.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ararshot.app.engine.BackgroundRemover
import com.ararshot.app.engine.BgReplaceConfig
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.PassportBgColor
import com.ararshot.app.engine.PassportPhotoMaker
import com.ararshot.app.engine.PassportSize
import com.ararshot.app.engine.TextOverlayConfig
import com.ararshot.app.engine.TextStyler
import com.ararshot.app.engine.TileProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ProcessState {
    object Idle : ProcessState()
    data class Processing(val progress: Float, val message: String) : ProcessState()
    data class Success(val bitmap: Bitmap, val message: String) : ProcessState()
    data class Error(val message: String) : ProcessState()
}

enum class EditorTab {
    AI_ENHANCE,
    MAGIC_ERASER,
    DEPTH_BOKEH_3D,
    BG_REMOVE,
    COLORIZE,
    ANIME_STYLE,
    PASSPORT_PHOTO,
    VINTAGE_CCD,
    TEXT_TYPOGRAPHY,
    COLOR_GRADING,
    CROP_TRANSFORM,
    EXPORT
}

data class ColorGradingState(
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val vignette: Float = 0f
)

class EnhancerViewModel : ViewModel() {
    private val TAG = "EnhancerViewModel"
    private val MAX_HISTORY = 8

    private val _processState = MutableStateFlow<ProcessState>(ProcessState.Idle)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _workingBitmap = MutableStateFlow<Bitmap?>(null)
    val workingBitmap: StateFlow<Bitmap?> = _workingBitmap.asStateFlow()

    private val _enhancedAiBitmap = MutableStateFlow<Bitmap?>(null)
    val enhancedAiBitmap: StateFlow<Bitmap?> = _enhancedAiBitmap.asStateFlow()

    private val _currentTab = MutableStateFlow(EditorTab.AI_ENHANCE)
    val currentTab: StateFlow<EditorTab> = _currentTab.asStateFlow()

    private val _colorState = MutableStateFlow(ColorGradingState())
    val colorState: StateFlow<ColorGradingState> = _colorState.asStateFlow()

    private val _bgConfig = MutableStateFlow(BgReplaceConfig())
    val bgConfig: StateFlow<BgReplaceConfig> = _bgConfig.asStateFlow()

    private val _textConfig = MutableStateFlow(TextOverlayConfig())
    val textConfig: StateFlow<TextOverlayConfig> = _textConfig.asStateFlow()

    private val _collageBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val collageBitmaps: StateFlow<List<Bitmap>> = _collageBitmaps.asStateFlow()

    private val _inpaintMask = MutableStateFlow<Bitmap?>(null)
    val inpaintMask: StateFlow<Bitmap?> = _inpaintMask.asStateFlow()

    // Non-destructive Undo / Redo History Stack
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var colorGradingJob: Job? = null

    private fun pushSnapshotForUndo(current: Bitmap?) {
        if (current == null) return
        if (undoStack.size >= MAX_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(current)
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    private fun commitNewBitmap(newBitmap: Bitmap) {
        pushSnapshotForUndo(_workingBitmap.value)
        _workingBitmap.value = newBitmap
        _enhancedAiBitmap.value = newBitmap
        if (_inpaintMask.value?.width != newBitmap.width || _inpaintMask.value?.height != newBitmap.height) {
            _inpaintMask.value = Bitmap.createBitmap(newBitmap.width, newBitmap.height, Bitmap.Config.ARGB_8888)
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _workingBitmap.value
        val previous = undoStack.removeLast()
        if (current != null) {
            redoStack.addLast(current)
        }
        _workingBitmap.value = previous
        _enhancedAiBitmap.value = if (previous == _originalBitmap.value) null else previous
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _workingBitmap.value
        val next = redoStack.removeLast()
        if (current != null) {
            undoStack.addLast(current)
        }
        _workingBitmap.value = next
        _enhancedAiBitmap.value = next
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun setCurrentTab(tab: EditorTab) {
        _currentTab.value = tab
    }

    fun setSourceBitmap(bitmap: Bitmap) {
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        _originalBitmap.value = bitmap
        _workingBitmap.value = bitmap
        _enhancedAiBitmap.value = null
        _colorState.value = ColorGradingState()
        _processState.value = ProcessState.Idle
        _inpaintMask.value = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    fun loadFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1536)
                withContext(Dispatchers.Main) {
                    setSourceBitmap(bmp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal memuat gambar: ${e.message}")
                }
            }
        }
    }

    /**
     * Executes AI Super-Resolution or Remini Face & Portrait Restoration via TileProcessor.
     */
    fun runAiEnhance(context: Context, mode: Int, strength: Float) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val initMsg = if (mode == ImageEnhancerNative.MODE_FACE_RESTORE) {
                "Mendeteksi wajah SCRFD & merestorasi pori/mata AI..."
            } else {
                "Menyiapkan NCNN Real-ESRGAN Engine..."
            }
            _processState.value = ProcessState.Processing(0.05f, initMsg)
            try {
                ImageEnhancerNative.initModels(context)

                TileProcessor.processTiledFlow(src, mode, strength).collect { progressData ->
                    if (progressData.partialBitmap != null) {
                        withContext(Dispatchers.Main) {
                            commitNewBitmap(progressData.partialBitmap)
                            _processState.value = ProcessState.Success(
                                progressData.partialBitmap,
                                "Peningkatan AI Berhasil Selesai!"
                            )
                        }
                    } else {
                        val percent = (progressData.progress * 100).toInt()
                        _processState.value = ProcessState.Processing(
                            progressData.progress,
                            "Memproses Tile AI (${progressData.currentTile}/${progressData.totalTiles}) - $percent%"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI enhance error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal memproses AI: ${e.message}")
                }
            }
        }
    }

    /**
     * Executes on-device AI Background Removal or Replacement (MODNet + Guided Filter + Color Decontamination).
     */
    fun runBackgroundReplace(config: BgReplaceConfig) {
        _bgConfig.value = config
        val src = _workingBitmap.value ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.2f, "AI MODNet & Guided Filter sedang memisahkan rambut/subjek...")
            try {
                val processed = BackgroundRemover.processBackground(src, config)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(processed)
                    _processState.value = ProcessState.Success(processed, "Latar belakang berhasil diproses!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background process error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal memproses latar belakang: ${e.message}")
                }
            }
        }
    }

    fun updateTextOverlay(config: TextOverlayConfig) {
        _textConfig.value = config
    }

    fun applyTextOverlay() {
        val src = _workingBitmap.value ?: return
        val config = _textConfig.value

        viewModelScope.launch(Dispatchers.Default) {
            val result = TextStyler.drawTextOnBitmap(src, config)
            withContext(Dispatchers.Main) {
                commitNewBitmap(result)
                _processState.value = ProcessState.Success(result, "Teks berhasil ditambahkan!")
            }
        }
    }

    /**
     * 1. Magic Eraser / Object Removal (Eikonal Telea FMM + Exemplar Patch Texture)
     */
    fun runMagicEraser(radius: Int = 6) {
        val src = _workingBitmap.value ?: return
        val mask = _inpaintMask.value ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.2f, "AI Magic Eraser sedang menghapus objek...")
            try {
                val result = ImageEnhancerNative.inpaint(src, mask, radius)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _inpaintMask.value = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                    _processState.value = ProcessState.Success(result, "Objek berhasil dihapus mulus!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Magic Eraser error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal menghapus objek: ${e.message}")
                }
            }
        }
    }

    fun clearInpaintMask() {
        val current = _workingBitmap.value ?: return
        _inpaintMask.value = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * 2. AI Depth Bokeh & 3D Relighting (powered by Depth Anything dpt256 NCNN + Geodesic 3D Normal Map)
     */
    fun runDepthBokeh(blurRadius: Float, focusDepth: Float = 1.0f) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Menghitung Depth Anything 3D Map & Optical Lens Bokeh...")
            try {
                val depthMap = BackgroundRemover.getDepthMap(src)
                val result = ImageEnhancerNative.depthBokeh(src, depthMap, blurRadius, focusDepth)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Efek DSLR Lens Bokeh berhasil diterapkan!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Depth Bokeh error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal memproses bokeh: ${e.message}")
                }
            }
        }
    }

    fun run3DRelighting(lightX: Float, lightY: Float, lightZ: Float, lightColor: Int, intensity: Float) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Menggeser arah cahaya studio 3D berbasis Depth Map...")
            try {
                val depthMap = BackgroundRemover.getDepthMap(src)
                val result = ImageEnhancerNative.relighting3D(src, depthMap, lightX, lightY, lightZ, lightColor, intensity)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Tata cahaya studio 3D berhasil diterapkan!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "3D Relighting error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal mengatur relighting: ${e.message}")
                }
            }
        }
    }

    /**
     * 3. Text Behind Subject (Sandwich Compositing)
     */
    fun runTextBehindSubject() {
        val src = _workingBitmap.value ?: return
        val config = _textConfig.value

        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Menyusun layer teks di belakang subjek...")
            try {
                val result = BackgroundRemover.renderTextBehindSubject(src, config)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Teks di belakang subjek berhasil dibuat!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Text Behind Subject error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal menaruh teks di belakang subjek: ${e.message}")
                }
            }
        }
    }

    /**
     * 4. Colorize Old B&W Photos (CIE LAB Perceptual Semantic Colorization)
     */
    fun runColorize(skinWarmth: Float = 1.0f, skyCyan: Float = 1.0f) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "AI sedang mewarnai foto hitam-putih (CIE LAB)...")
            try {
                val personMask = BackgroundRemover.getFloatMask(src)
                val result = ImageEnhancerNative.colorizeGrayscale(src, personMask, skinWarmth, skyCyan)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Foto hitam-putih berhasil diwarnai secara alami!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Colorize error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal mewarnai foto: ${e.message}")
                }
            }
        }
    }

    /**
     * 5. AI Anime / Ghibli Style Transfer (AnimeGANv2 NCNN + Anisotropic Kuwahara + DoG Ink)
     */
    fun runAnimeCelShade(radius: Int = 4, edgeStrength: Float = 0.8f, vibrancy: Float = 1.0f) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Menerapkan AnimeGANv2 & Cel-Shading Kuwahara...")
            try {
                val result = ImageEnhancerNative.animeCelShade(src, radius, edgeStrength, vibrancy)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Foto berhasil diubah ke gaya Anime / Ghibli!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Anime style error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal menerapkan gaya anime: ${e.message}")
                }
            }
        }
    }

    /**
     * 6. AI Passport / ID Photo Maker & 4R Sheet (with SCRFD Biometric Eye-Line Centering)
     */
    fun runPassportPhoto(size: PassportSize, bgColor: PassportBgColor) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Membuat pasfoto resmi ${size.displayName} (SCRFD Auto-Center)...")
            try {
                val result = PassportPhotoMaker.generateSinglePassport(src, size, bgColor)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Pasfoto ${size.displayName} berhasil dibuat!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Passport error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal membuat pasfoto: ${e.message}")
                }
            }
        }
    }

    fun generatePassport4RSheet(size: PassportSize, bgColor: PassportBgColor) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.4f, "Menyusun lembar cetak 4R grid...")
            try {
                val single = PassportPhotoMaker.generateSinglePassport(src, size, bgColor)
                val sheet = PassportPhotoMaker.generate4RPrintSheet(single, size)
                withContext(Dispatchers.Main) {
                    commitNewBitmap(sheet)
                    _processState.value = ProcessState.Success(sheet, "Lembar cetak 4R siap print berhasil dibuat!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "4R Sheet error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal menyusun lembar cetak: ${e.message}")
                }
            }
        }
    }

    /**
     * 7. Vintage CCD / Film Halation & Retro Date Stamp
     */
    fun runVintageHalation(
        radius: Float = 14f,
        grain: Float = 0.5f,
        chromatic: Float = 0.6f,
        warmth: Float = 1.0f,
        dateStamp: String? = "'98 10 14"
    ) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Menerapkan Red Halation, Film Grain & Nada CCD 2000-an...")
            try {
                var result = ImageEnhancerNative.vintageHalation(src, radius, grain, chromatic, warmth)
                if (!dateStamp.isNullOrBlank()) {
                    result = ImageUtils.addRetroDateStamp(result, dateStamp)
                }
                withContext(Dispatchers.Main) {
                    commitNewBitmap(result)
                    _processState.value = ProcessState.Success(result, "Gaya Vintage Digicam CCD berhasil diterapkan!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vintage halation error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.Error("Gagal menerapkan gaya vintage: ${e.message}")
                }
            }
        }
    }

    /**
     * Applies Color Grading with coroutine job cancellation to prevent slider race conditions.
     */
    fun updateColorGrading(newColorState: ColorGradingState) {
        _colorState.value = newColorState
        val base = _enhancedAiBitmap.value ?: _originalBitmap.value ?: return

        colorGradingJob?.cancel()
        colorGradingJob = viewModelScope.launch(Dispatchers.Default) {
            val graded = ImageUtils.applyColorGrading(
                src = base,
                exposure = newColorState.exposure,
                contrast = newColorState.contrast,
                saturation = newColorState.saturation,
                temperature = newColorState.temperature,
                vignette = newColorState.vignette
            )
            withContext(Dispatchers.Main) {
                _workingBitmap.value = graded
            }
        }
    }

    /**
     * Transforms: Rotate, Flip, Crop.
     */
    fun rotateImage() {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val rotated = ImageUtils.rotateBitmap(current, 90f)
            withContext(Dispatchers.Main) {
                commitNewBitmap(rotated)
            }
        }
    }

    fun flipHorizontal() {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val flipped = ImageUtils.flipBitmap(current, horizontal = true, vertical = false)
            withContext(Dispatchers.Main) {
                commitNewBitmap(flipped)
            }
        }
    }

    fun cropAspect(aspectX: Int, aspectY: Int) {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val cropped = ImageUtils.cropToAspectRatio(current, aspectX, aspectY)
            withContext(Dispatchers.Main) {
                commitNewBitmap(cropped)
            }
        }
    }

    /**
     * Collage Studio State
     */
    fun addCollagePhoto(bitmap: Bitmap) {
        val current = _collageBitmaps.value.toMutableList()
        if (current.size < 4) {
            current.add(bitmap)
            _collageBitmaps.value = current
        }
    }

    fun clearCollage() {
        _collageBitmaps.value = emptyList()
    }

    /**
     * Saves final processed bitmap to gallery (auto-detecting lossless PNG for transparent cutouts).
     */
    fun saveToGallery(context: Context, onResult: (Boolean) -> Unit) {
        val bmp = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = ImageUtils.saveBitmapToGallery(context, bmp)
            withContext(Dispatchers.Main) {
                onResult(success)
            }
        }
    }

    fun dismissState() {
        _processState.value = ProcessState.Idle
    }
}
