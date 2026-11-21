package com.ararshot.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
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
import com.ararshot.app.model.ColorAdjustments
import com.ararshot.app.model.EditorTool
import com.ararshot.app.model.ExportSizeOption
import com.ararshot.app.model.FilterPreset
import com.ararshot.app.model.GalleryPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ProcessState {
    object Idle : ProcessState()
    data class Processing(val progress: Float, val message: String) : ProcessState()
    data class ToastMessage(val message: String, val isError: Boolean = false) : ProcessState()
}

private data class EditSnapshot(
    val baseBitmap: Bitmap,
    val adjustments: ColorAdjustments
)

class EnhancerViewModel : ViewModel() {
    private val tag = "ArArViewModel"
    private val maxHistory = 14

    private val _processState = MutableStateFlow<ProcessState>(ProcessState.Idle)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val _recentPhotos = MutableStateFlow<List<GalleryPhoto>>(emptyList())
    val recentPhotos: StateFlow<List<GalleryPhoto>> = _recentPhotos.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    // Base raster bitmap before non-destructive Linear-Light ColorAdjustments
    private var baseBitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var hasStructuralEdits: Boolean = false

    private val _adjustments = MutableStateFlow(ColorAdjustments())
    val adjustments: StateFlow<ColorAdjustments> = _adjustments.asStateFlow()

    private val _workingBitmap = MutableStateFlow<Bitmap?>(null)
    val workingBitmap: StateFlow<Bitmap?> = _workingBitmap.asStateFlow()

    private val _activeTool = MutableStateFlow(EditorTool.ADJUST)
    val activeTool: StateFlow<EditorTool> = _activeTool.asStateFlow()

    private val _filterThumbnails = MutableStateFlow<Map<FilterPreset, Bitmap>>(emptyMap())
    val filterThumbnails: StateFlow<Map<FilterPreset, Bitmap>> = _filterThumbnails.asStateFlow()

    private val _bgConfig = MutableStateFlow(BgReplaceConfig())
    val bgConfig: StateFlow<BgReplaceConfig> = _bgConfig.asStateFlow()

    private val _textConfig = MutableStateFlow(TextOverlayConfig())
    val textConfig: StateFlow<TextOverlayConfig> = _textConfig.asStateFlow()

    private val _collageBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val collageBitmaps: StateFlow<List<Bitmap>> = _collageBitmaps.asStateFlow()

    private val _inpaintMask = MutableStateFlow<Bitmap?>(null)
    val inpaintMask: StateFlow<Bitmap?> = _inpaintMask.asStateFlow()

    // Unified non-destructive + structural Undo / Redo
    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // Per-bitmap cached AI maps so slider tweaks & BG switches are fast
    private var cachedMaskSource: Bitmap? = null
    private var cachedAlphaMatte: FloatArray? = null
    private var cachedDepthSource: Bitmap? = null
    private var cachedDepthMap: FloatArray? = null

    private var colorRenderJob: Job? = null
    private var filterThumbJob: Job? = null
    private var pendingUndoBaseline: ColorAdjustments? = null

    fun refreshRecentPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val photos = ImageUtils.queryRecentPhotos(context, limit = 60)
            withContext(Dispatchers.Main) {
                _recentPhotos.value = photos
            }
        }
    }

    fun selectTool(tool: EditorTool) {
        _activeTool.value = tool
    }

    private fun syncUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun pushSnapshot(bmp: Bitmap, adj: ColorAdjustments) {
        if (undoStack.size >= maxHistory) undoStack.removeFirst()
        undoStack.addLast(EditSnapshot(bmp, adj))
        redoStack.clear()
        syncUndoFlags()
    }

    private fun invalidateAiCaches() {
        cachedMaskSource = null
        cachedAlphaMatte = null
        cachedDepthSource = null
        cachedDepthMap = null
    }

    /**
     * Bakes current color adjustments into baseBitmap before a structural/AI edit so edits stack naturally.
     */
    private fun bakedCurrentBitmap(): Bitmap? {
        return _workingBitmap.value ?: baseBitmap
    }

    private fun commitStructuralBitmap(newBitmap: Bitmap, message: String? = null) {
        val prevBase = baseBitmap
        val prevAdj = _adjustments.value
        if (prevBase != null) {
            pushSnapshot(prevBase, prevAdj)
        }
        baseBitmap = newBitmap
        hasStructuralEdits = true
        _adjustments.value = ColorAdjustments()
        _workingBitmap.value = newBitmap
        invalidateAiCaches()
        ensureInpaintMask(newBitmap)
        generateFilterThumbnails(newBitmap)
        if (message != null) {
            _processState.value = ProcessState.ToastMessage(message)
        } else {
            _processState.value = ProcessState.Idle
        }
    }

    private fun ensureInpaintMask(bmp: Bitmap) {
        if (_inpaintMask.value?.width != bmp.width || _inpaintMask.value?.height != bmp.height) {
            _inpaintMask.value = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        }
    }

    fun setSourceBitmap(bitmap: Bitmap, uri: Uri? = null) {
        colorRenderJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        syncUndoFlags()
        invalidateAiCaches()
        sourceUri = uri
        hasStructuralEdits = false
        _originalBitmap.value = bitmap
        baseBitmap = bitmap
        _adjustments.value = ColorAdjustments()
        _workingBitmap.value = bitmap
        _processState.value = ProcessState.Idle
        ensureInpaintMask(bitmap)
        generateFilterThumbnails(bitmap)
    }

    fun loadFromUri(context: Context, uri: Uri, initialTool: EditorTool = EditorTool.ADJUST) {
        _activeTool.value = initialTool
        _processState.value = ProcessState.Processing(0.2f, "Membuka foto...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Keep sharp preview up to 1920px; original URI preserved for full-res export
                val bmp = ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1920)
                withContext(Dispatchers.Main) {
                    setSourceBitmap(bmp, uri)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load URI: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal membuka foto", isError = true)
                }
            }
        }
    }

    fun resetAll() {
        val orig = _originalBitmap.value ?: return
        val prevBase = baseBitmap
        if (prevBase != null && (prevBase != orig || !_adjustments.value.isIdentity())) {
            pushSnapshot(prevBase, _adjustments.value)
        }
        baseBitmap = orig
        hasStructuralEdits = false
        _adjustments.value = ColorAdjustments()
        _workingBitmap.value = orig
        invalidateAiCaches()
        ensureInpaintMask(orig)
        generateFilterThumbnails(orig)
        _processState.value = ProcessState.ToastMessage("Dikembalikan ke foto asli")
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val curBase = baseBitmap ?: return
        val curAdj = _adjustments.value
        redoStack.addLast(EditSnapshot(curBase, curAdj))

        val prev = undoStack.removeLast()
        baseBitmap = prev.baseBitmap
        _adjustments.value = prev.adjustments
        invalidateAiCaches()
        ensureInpaintMask(prev.baseBitmap)
        syncUndoFlags()
        renderAdjustments(prev.adjustments, immediate = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val curBase = baseBitmap ?: return
        val curAdj = _adjustments.value
        undoStack.addLast(EditSnapshot(curBase, curAdj))

        val next = redoStack.removeLast()
        baseBitmap = next.baseBitmap
        _adjustments.value = next.adjustments
        invalidateAiCaches()
        ensureInpaintMask(next.baseBitmap)
        syncUndoFlags()
        renderAdjustments(next.adjustments, immediate = true)
    }

    // -----------------------------------------------------------------------
    // Non-Destructive Linear-Light Adjustments, HSL & Curated Filters
    // -----------------------------------------------------------------------

    fun beginSliderDrag() {
        if (pendingUndoBaseline == null) {
            pendingUndoBaseline = _adjustments.value
        }
    }

    fun endSliderDrag() {
        val baseline = pendingUndoBaseline
        pendingUndoBaseline = null
        val curBase = baseBitmap ?: return
        if (baseline != null && baseline != _adjustments.value) {
            pushSnapshot(curBase, baseline)
        }
    }

    fun updateAdjustments(transform: (ColorAdjustments) -> ColorAdjustments, recordUndoImmediately: Boolean = false) {
        val curBase = baseBitmap ?: return
        val old = _adjustments.value
        val updated = transform(old)
        if (updated == old) return
        if (recordUndoImmediately) {
            pushSnapshot(curBase, old)
        }
        _adjustments.value = updated
        renderAdjustments(updated, immediate = recordUndoImmediately)
    }

    fun runAutoEnhance() {
        val curBase = baseBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val old = _adjustments.value
            val autoAdj = ImageEnhancerNative.analyzeAutoEnhance(curBase, old)
            val rendered = ImageEnhancerNative.applyLinearColor(curBase, autoAdj)
            withContext(Dispatchers.Main) {
                pushSnapshot(curBase, old)
                _adjustments.value = autoAdj
                _workingBitmap.value = rendered
                _processState.value = ProcessState.ToastMessage("Penyesuaian otomatis diterapkan")
            }
        }
    }

    private fun renderAdjustments(adj: ColorAdjustments, immediate: Boolean) {
        val curBase = baseBitmap ?: return
        colorRenderJob?.cancel()
        if (adj.isIdentity()) {
            _workingBitmap.value = curBase
            return
        }
        colorRenderJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(18) // Coalesce rapid 120Hz touch moves cleanly
            val rendered = ImageEnhancerNative.applyLinearColor(curBase, adj)
            withContext(Dispatchers.Main) {
                _workingBitmap.value = rendered
            }
        }
    }

    private fun generateFilterThumbnails(source: Bitmap) {
        filterThumbJob?.cancel()
        filterThumbJob = viewModelScope.launch(Dispatchers.Default) {
            val thumb = ImageUtils.resizeToMaxEdge(source, 140)
            val map = LinkedHashMap<FilterPreset, Bitmap>(FilterPreset.entries.size)
            for (preset in FilterPreset.entries) {
                map[preset] = if (preset == FilterPreset.NONE) {
                    thumb
                } else {
                    ImageEnhancerNative.applyLinearColor(
                        thumb,
                        ColorAdjustments(preset = preset, presetStrength = 0.85f)
                    )
                }
            }
            withContext(Dispatchers.Main) {
                _filterThumbnails.value = map
            }
        }
    }

    // -----------------------------------------------------------------------
    // Crop, Straighten, Rotate & Flip
    // -----------------------------------------------------------------------

    fun applyCropAndStraighten(cropNormalized: RectF, straightenDegrees: Float) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val out = ImageUtils.cropAndStraighten(src, cropNormalized, straightenDegrees)
            withContext(Dispatchers.Main) {
                commitStructuralBitmap(out, "Foto dipotong")
            }
        }
    }

    fun rotateImage(degrees: Float = 90f) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val out = ImageUtils.rotateBitmap(src, degrees)
            withContext(Dispatchers.Main) {
                commitStructuralBitmap(out)
            }
        }
    }

    fun flipHorizontal() {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val out = ImageUtils.flipBitmap(src, horizontal = true, vertical = false)
            withContext(Dispatchers.Main) {
                commitStructuralBitmap(out)
            }
        }
    }

    // -----------------------------------------------------------------------
    // AI Enhance, Anti-Noise, Face Restore & 4X Upscale
    // -----------------------------------------------------------------------

    fun runAiEnhance(context: Context, mode: Int, strength: Float) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val msg = when (mode) {
                ImageEnhancerNative.MODE_FACE_RESTORE -> "Memperjelas wajah..."
                ImageEnhancerNative.MODE_DENOISE_ONLY, ImageEnhancerNative.MODE_BILATERAL_DENOISE -> "Membersihkan bintik noise..."
                ImageEnhancerNative.MODE_UNSHARP_MASK -> "Menajamkan detail..."
                else -> "Meningkatkan resolusi..."
            }
            _processState.value = ProcessState.Processing(0.1f, msg)
            try {
                ImageEnhancerNative.initEngine(context)
                TileProcessor.processTiledFlow(src, mode, strength).collect { progressData ->
                    if (progressData.partialBitmap != null) {
                        withContext(Dispatchers.Main) {
                            commitStructuralBitmap(progressData.partialBitmap, "Peningkatan selesai")
                        }
                    } else {
                        _processState.value = ProcessState.Processing(progressData.progress, msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Enhance error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal memproses foto", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Background Removal & Replacement (Cached Subject Segmentation + MODNet)
    // -----------------------------------------------------------------------

    private suspend fun getOrComputeAlphaMatte(src: Bitmap): FloatArray {
        val cached = cachedAlphaMatte
        if (cachedMaskSource === src && cached != null && cached.size == src.width * src.height) {
            return cached
        }
        val mask = BackgroundRemover.getFloatMask(src)
        cachedMaskSource = src
        cachedAlphaMatte = mask
        return mask
    }

    private suspend fun getOrComputeDepthMap(src: Bitmap): FloatArray {
        val cached = cachedDepthMap
        if (cachedDepthSource === src && cached != null && cached.size == src.width * src.height) {
            return cached
        }
        val alpha = getOrComputeAlphaMatte(src)
        val depth = ImageEnhancerNative.estimateDepth(src, alpha)
        cachedDepthSource = src
        cachedDepthMap = depth
        return depth
    }

    fun runBackgroundReplace(context: Context, config: BgReplaceConfig) {
        _bgConfig.value = config
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Memisahkan objek...")
            try {
                ImageEnhancerNative.initEngine(context)
                val matte = getOrComputeAlphaMatte(src)
                _processState.value = ProcessState.Processing(0.65f, "Menerapkan latar belakang...")
                val processed = BackgroundRemover.processBackground(src, config, cachedAlphaMatte = matte)
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(processed, "Latar belakang diperbarui")
                }
            } catch (e: Exception) {
                Log.e(tag, "BG replace error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal memproses latar", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Depth Bokeh & 3D Lighting
    // -----------------------------------------------------------------------

    fun runDepthBokeh(context: Context, blurRadius: Float, focusDepth: Float) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Menghitung kedalaman foto...")
            try {
                ImageEnhancerNative.initEngine(context)
                val depth = getOrComputeDepthMap(src)
                _processState.value = ProcessState.Processing(0.65f, "Menerapkan efek blur...")
                val result = ImageEnhancerNative.depthBokeh(src, depth, blurRadius, focusDepth)
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(result, "Efek blur diterapkan")
                }
            } catch (e: Exception) {
                Log.e(tag, "Bokeh error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal menerapkan blur", isError = true)
                }
            }
        }
    }

    fun run3DRelighting(
        context: Context,
        lightX: Float,
        lightY: Float,
        lightZ: Float,
        lightColor: Int,
        intensity: Float
    ) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.25f, "Menghitung arah cahaya...")
            try {
                ImageEnhancerNative.initEngine(context)
                val depth = getOrComputeDepthMap(src)
                _processState.value = ProcessState.Processing(0.70f, "Menerapkan pencahayaan...")
                val result = ImageEnhancerNative.relighting3D(src, depth, lightX, lightY, lightZ, lightColor, intensity)
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(result, "Pencahayaan diterapkan")
                }
            } catch (e: Exception) {
                Log.e(tag, "Relight error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal menerapkan cahaya", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Object Removal (Inpainting)
    // -----------------------------------------------------------------------

    fun runObjectErase(radius: Int = 7) {
        val src = bakedCurrentBitmap() ?: return
        val mask = _inpaintMask.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Menghapus objek...")
            try {
                val result = ImageEnhancerNative.inpaint(src, mask, radius)
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(result, "Objek dihapus")
                }
            } catch (e: Exception) {
                Log.e(tag, "Erase error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal menghapus objek", isError = true)
                }
            }
        }
    }

    fun clearInpaintMask() {
        val current = _workingBitmap.value ?: return
        _inpaintMask.value = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
    }

    // -----------------------------------------------------------------------
    // Anime / Illustration / Sketch
    // -----------------------------------------------------------------------

    fun runAnimeStylize(context: Context, styleMode: Int, strength: Float) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Menerapkan gaya anime...")
            try {
                ImageEnhancerNative.initEngine(context)
                val result = ImageEnhancerNative.animeStylize(src, styleMode, strength)
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(result, "Gaya diterapkan")
                }
            } catch (e: Exception) {
                Log.e(tag, "Anime error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal memproses gaya anime", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Text Overlay & Text Behind Subject
    // -----------------------------------------------------------------------

    fun updateTextConfig(config: TextOverlayConfig) {
        _textConfig.value = config
    }

    fun applyTextOverlay(context: Context) {
        val src = bakedCurrentBitmap() ?: return
        val config = _textConfig.value
        if (config.text.isBlank()) {
            _processState.value = ProcessState.ToastMessage("Tulis teks terlebih dahulu", isError = true)
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.35f, "Menambahkan teks...")
            try {
                val result = if (config.behindSubject) {
                    ImageEnhancerNative.initEngine(context)
                    val matte = getOrComputeAlphaMatte(src)
                    BackgroundRemover.renderTextBehindSubject(src, config, cachedAlphaMatte = matte)
                } else {
                    TextStyler.drawTextOnBitmap(src, config)
                }
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(result, "Teks ditambahkan")
                }
            } catch (e: Exception) {
                Log.e(tag, "Text error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal menambahkan teks", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Passport Photo
    // -----------------------------------------------------------------------

    fun runPassportPhoto(context: Context, size: PassportSize, bgColor: PassportBgColor, as4RSheet: Boolean) {
        val src = bakedCurrentBitmap() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _processState.value = ProcessState.Processing(0.3f, "Menyiapkan pasfoto ${size.displayName}...")
            try {
                ImageEnhancerNative.initEngine(context)
                val matte = getOrComputeAlphaMatte(src)
                val single = PassportPhotoMaker.generateSinglePassport(src, size, bgColor, cachedAlphaMatte = matte)
                val out = if (as4RSheet) PassportPhotoMaker.generate4RPrintSheet(single, size) else single
                withContext(Dispatchers.Main) {
                    commitStructuralBitmap(
                        out,
                        if (as4RSheet) "Lembar cetak 4R siap disimpan" else "Pasfoto ${size.displayName} siap"
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Passport error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _processState.value = ProcessState.ToastMessage("Gagal membuat pasfoto", isError = true)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Export (Full-Res aware) & Share
    // -----------------------------------------------------------------------

    private suspend fun buildExportBitmap(context: Context, sizeOption: ExportSizeOption): Bitmap? =
        withContext(Dispatchers.IO) {
            val adj = _adjustments.value
            val uri = sourceUri
            // If only non-destructive color/filter edits were applied and original URI exists,
            // render directly from full-res original up to sizeOption.maxEdgePx!
            val baseForExport = if (!hasStructuralEdits && uri != null && sizeOption == ExportSizeOption.ORIGINAL) {
                try {
                    ImageUtils.loadBitmapFromUri(context, uri, maxDim = sizeOption.maxEdgePx)
                } catch (_: Exception) {
                    baseBitmap
                }
            } else {
                baseBitmap
            } ?: return@withContext null

            val sized = ImageUtils.resizeToMaxEdge(baseForExport, sizeOption.maxEdgePx)
            if (adj.isIdentity()) sized else ImageEnhancerNative.applyLinearColor(sized, adj)
        }

    fun exportToGallery(
        context: Context,
        sizeOption: ExportSizeOption,
        forcePng: Boolean = false,
        jpegQuality: Int = 95,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _processState.value = ProcessState.Processing(0.4f, "Menyimpan foto...")
            val bmp = buildExportBitmap(context, sizeOption)
            val ok = bmp != null && ImageUtils.saveBitmapToGallery(
                context = context,
                bitmap = bmp,
                forcePng = forcePng,
                jpegQuality = jpegQuality
            )
            withContext(Dispatchers.Main) {
                _processState.value = if (ok) {
                    ProcessState.ToastMessage("Tersimpan di Galeri (Pictures/ArArShot)")
                } else {
                    ProcessState.ToastMessage("Gagal menyimpan foto", isError = true)
                }
                onComplete(ok)
            }
        }
    }

    fun prepareShareUri(
        context: Context,
        sizeOption: ExportSizeOption,
        forcePng: Boolean = false,
        onReady: (Uri?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _processState.value = ProcessState.Processing(0.4f, "Menyiapkan file...")
            val bmp = buildExportBitmap(context, sizeOption)
            val uri = bmp?.let { ImageUtils.createShareableUri(context, it, forcePng) }
            withContext(Dispatchers.Main) {
                _processState.value = ProcessState.Idle
                onReady(uri)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Collage Studio State
    // -----------------------------------------------------------------------

    fun addCollagePhoto(bitmap: Bitmap) {
        val current = _collageBitmaps.value.toMutableList()
        if (current.size < 4) {
            current.add(bitmap)
            _collageBitmaps.value = current
        }
    }

    fun removeCollagePhotoAt(index: Int) {
        val current = _collageBitmaps.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _collageBitmaps.value = current
        }
    }

    fun clearCollage() {
        _collageBitmaps.value = emptyList()
    }

    fun dismissToast() {
        if (_processState.value is ProcessState.ToastMessage) {
            _processState.value = ProcessState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        ImageEnhancerNative.releaseMemory()
    }
}
