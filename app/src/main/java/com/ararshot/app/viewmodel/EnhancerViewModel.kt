package com.ararshot.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ararshot.app.engine.ImageEnhancerNative
import com.ararshot.app.engine.ImageUtils
import com.ararshot.app.engine.TileProcessor
import kotlinx.coroutines.Dispatchers
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

    // Collage Studio State
    private val _collageBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val collageBitmaps: StateFlow<List<Bitmap>> = _collageBitmaps.asStateFlow()

    fun setCurrentTab(tab: EditorTab) {
        _currentTab.value = tab
    }

    fun setSourceBitmap(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
        _workingBitmap.value = bitmap
        _enhancedAiBitmap.value = null
        _colorState.value = ColorGradingState()
        _processState.value = ProcessState.Idle
    }

    fun loadFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = ImageUtils.loadBitmapFromUri(context, uri, maxDim = 1024)
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
     * Executes AI Enhancement using TileProcessor with real-time progress callbacks.
     */
    fun runAiEnhance(context: Context, mode: Int, strength: Float) {
        val src = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _processState.value = ProcessState.Processing(0.05f, "Menyiapkan AI Engine...")
            try {
                ImageEnhancerNative.initModels(context)

                TileProcessor.processTiledFlow(src, mode, strength).collect { progressData ->
                    if (progressData.partialBitmap != null) {
                        withContext(Dispatchers.Main) {
                            _workingBitmap.value = progressData.partialBitmap
                            _enhancedAiBitmap.value = progressData.partialBitmap
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
     * Applies Color Grading to the current base image.
     */
    fun updateColorGrading(newColorState: ColorGradingState) {
        _colorState.value = newColorState
        val base = _enhancedAiBitmap.value ?: _originalBitmap.value ?: return

        viewModelScope.launch(Dispatchers.Default) {
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
     * Rotates the working image by 90 degrees.
     */
    fun rotateImage() {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val rotated = ImageUtils.rotateBitmap(current, 90f)
            withContext(Dispatchers.Main) {
                _workingBitmap.value = rotated
                _enhancedAiBitmap.value = rotated
            }
        }
    }

    /**
     * Flips image horizontally.
     */
    fun flipHorizontal() {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val flipped = ImageUtils.flipBitmap(current, horizontal = true, vertical = false)
            withContext(Dispatchers.Main) {
                _workingBitmap.value = flipped
                _enhancedAiBitmap.value = flipped
            }
        }
    }

    /**
     * Crops image to aspect ratio.
     */
    fun cropAspect(aspectX: Int, aspectY: Int) {
        val current = _workingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val cropped = ImageUtils.cropToAspectRatio(current, aspectX, aspectY)
            withContext(Dispatchers.Main) {
                _workingBitmap.value = cropped
                _enhancedAiBitmap.value = cropped
            }
        }
    }

    /**
     * Adds photo to collage studio.
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
     * Saves final processed bitmap to gallery.
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
