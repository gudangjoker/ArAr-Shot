#include "native_enhancer.h"

#if USE_NCNN
static ncnn::Net* g_net = nullptr;
static bool g_model_loaded = false;
static bool g_has_vulkan = false;
static std::mutex g_model_mutex;
#if NCNN_VULKAN
static bool g_vulkan_initialized = false;
#endif
#endif

static inline uint8_t clamp8(float v) {
    if (v < 0.0f) return 0;
    if (v > 255.0f) return 255;
    return static_cast<uint8_t>(v + 0.5f);
}

static inline uint8_t clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return static_cast<uint8_t>(v);
}

// ---------------------------------------------------------------------------
// Native Mathematical Algorithms (Fallbacks and Specific Modes)
// ---------------------------------------------------------------------------

void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::max(1, static_cast<int>(radius + 0.5f));
    std::vector<float> kernel(2 * r + 1);
    float sigma = radius;
    float sum = 0.0f;
    for (int i = -r; i <= r; ++i) {
        float val = std::exp(-(i * i) / (2.0f * sigma * sigma));
        kernel[i + r] = val;
        sum += val;
    }
    for (size_t i = 0; i < kernel.size(); ++i) {
        kernel[i] /= sum;
    }

    std::vector<uint32_t> temp(width * height);

    // Horizontal Gaussian Pass
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float rAcc = 0.0f, gAcc = 0.0f, bAcc = 0.0f;
            for (int k = -r; k <= r; ++k) {
                int nx = std::clamp(x + k, 0, width - 1);
                uint32_t p = src[y * width + nx];
                float w = kernel[k + r];
                rAcc += ((p >> 16) & 0xFF) * w;
                gAcc += ((p >> 8) & 0xFF) * w;
                bAcc += (p & 0xFF) * w;
            }
            uint32_t a = (src[y * width + x] >> 24) & 0xFF;
            temp[y * width + x] = (a << 24) | (clamp8(rAcc) << 16) | (clamp8(gAcc) << 8) | clamp8(bAcc);
        }
    }

    // Vertical Gaussian Pass & Difference Blending
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float rBlur = 0.0f, gBlur = 0.0f, bBlur = 0.0f;
            for (int k = -r; k <= r; ++k) {
                int ny = std::clamp(y + k, 0, height - 1);
                uint32_t p = temp[ny * width + x];
                float w = kernel[k + r];
                rBlur += ((p >> 16) & 0xFF) * w;
                gBlur += ((p >> 8) & 0xFF) * w;
                bBlur += (p & 0xFF) * w;
            }

            uint32_t orig = src[y * width + x];
            uint8_t a = (orig >> 24) & 0xFF;
            float rOrig = (orig >> 16) & 0xFF;
            float gOrig = (orig >> 8) & 0xFF;
            float bOrig = orig & 0xFF;

            float rSharp = rOrig + amount * (rOrig - rBlur);
            float gSharp = gOrig + amount * (gOrig - gBlur);
            float bSharp = bOrig + amount * (bOrig - bBlur);

            dst[y * width + x] = (static_cast<uint32_t>(a) << 24) |
                                 (static_cast<uint32_t>(clamp8(rSharp)) << 16) |
                                 (static_cast<uint32_t>(clamp8(gSharp)) << 8) |
                                 static_cast<uint32_t>(clamp8(bSharp));
        }
    }
}

void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma) {
    if (!src || !dst || width <= 0 || height <= 0) return;

    int r = std::max(1, static_cast<int>(spatialSigma * 1.5f));
    float twoSpatialSigmaSq = 2.0f * spatialSigma * spatialSigma;
    float twoColorSigmaSq = 2.0f * colorSigma * colorSigma;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            uint32_t centerP = src[y * width + x];
            float cR = (centerP >> 16) & 0xFF;
            float cG = (centerP >> 8) & 0xFF;
            float cB = centerP & 0xFF;
            uint32_t a = (centerP >> 24) & 0xFF;

            float sumWeights = 0.0f;
            float accR = 0.0f, accG = 0.0f, accB = 0.0f;

            for (int dy = -r; dy <= r; ++dy) {
                int ny = std::clamp(y + dy, 0, height - 1);
                for (int dx = -r; dx <= r; ++dx) {
                    int nx = std::clamp(x + dx, 0, width - 1);
                    uint32_t neighborP = src[ny * width + nx];
                    float nR = (neighborP >> 16) & 0xFF;
                    float nG = (neighborP >> 8) & 0xFF;
                    float nB = neighborP & 0xFF;

                    float spatialDistSq = static_cast<float>(dx * dx + dy * dy);
                    float colorDistSq = (cR - nR) * (cR - nR) + (cG - nG) * (cG - nG) + (cB - nB) * (cB - nB);

                    float weight = std::exp(-spatialDistSq / twoSpatialSigmaSq - colorDistSq / twoColorSigmaSq);
                    accR += nR * weight;
                    accG += nG * weight;
                    accB += nB * weight;
                    sumWeights += weight;
                }
            }

            if (sumWeights > 0.00001f) {
                accR /= sumWeights;
                accG /= sumWeights;
                accB /= sumWeights;
            }

            dst[y * width + x] = (a << 24) | (clamp8(accR) << 16) | (clamp8(accG) << 8) | clamp8(accB);
        }
    }
}

static inline float cubicWeight(float x) {
    const float a = -0.5f;
    x = std::fabs(x);
    if (x <= 1.0f) {
        return (a + 2.0f) * x * x * x - (a + 3.0f) * x * x + 1.0f;
    } else if (x < 2.0f) {
        return a * x * x * x - 5.0f * a * x * x + 8.0f * a * x - 4.0f * a;
    }
    return 0.0f;
}

void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount) {
    int dstW = srcW * scale;
    int dstH = srcH * scale;

    std::vector<uint32_t> upscaled(dstW * dstH);

    for (int y = 0; y < dstH; ++y) {
        float srcY = (y + 0.5f) / scale - 0.5f;
        int yInt = static_cast<int>(std::floor(srcY));
        float yFrac = srcY - yInt;

        for (int x = 0; x < dstW; ++x) {
            float srcX = (x + 0.5f) / scale - 0.5f;
            int xInt = static_cast<int>(std::floor(srcX));
            float xFrac = srcX - xInt;

            float rTotal = 0.0f, gTotal = 0.0f, bTotal = 0.0f, totalWeight = 0.0f;

            for (int m = -1; m <= 2; ++m) {
                int sampleY = std::clamp(yInt + m, 0, srcH - 1);
                float wy = cubicWeight(m - yFrac);

                for (int n = -1; n <= 2; ++n) {
                    int sampleX = std::clamp(xInt + n, 0, srcW - 1);
                    float wx = cubicWeight(n - xFrac);
                    float w = wy * wx;

                    uint32_t p = src[sampleY * srcW + sampleX];
                    rTotal += ((p >> 16) & 0xFF) * w;
                    gTotal += ((p >> 8) & 0xFF) * w;
                    bTotal += (p & 0xFF) * w;
                    totalWeight += w;
                }
            }

            if (totalWeight > 0.0001f) {
                rTotal /= totalWeight;
                gTotal /= totalWeight;
                bTotal /= totalWeight;
            }

            upscaled[y * dstW + x] = 0xFF000000 | (clamp8(rTotal) << 16) | (clamp8(gTotal) << 8) | clamp8(bTotal);
        }
    }

    // Apply smart sharpening to enhance details in upscale
    unsharpMask(upscaled.data(), dst, dstW, dstH, sharpenAmount, 1.2f);
}

void cosineTileBlend(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                     uint32_t* target, float* weightMap, int targetW, int targetH) {
    const float PI = 3.14159265358979323846f;
    for (int y = 0; y < tileH; ++y) {
        int ty = dstY + y;
        if (ty < 0 || ty >= targetH) continue;
        float wy = std::sin((y + 0.5f) * PI / tileH);

        for (int x = 0; x < tileW; ++x) {
            int tx = dstX + x;
            if (tx < 0 || tx >= targetW) continue;
            float wx = std::sin((x + 0.5f) * PI / tileW);
            float weight = wx * wy;

            int targetIdx = ty * targetW + tx;
            uint32_t tp = tile[y * tileW + x];

            float tR = ((tp >> 16) & 0xFF) * weight;
            float tG = ((tp >> 8) & 0xFF) * weight;
            float tB = (tp & 0xFF) * weight;

            float curWeight = weightMap[targetIdx];
            uint32_t curP = target[targetIdx];
            float curR = ((curP >> 16) & 0xFF) * curWeight;
            float curG = ((curP >> 8) & 0xFF) * curWeight;
            float curB = (curP & 0xFF) * curWeight;

            float newWeight = curWeight + weight;
            if (newWeight > 0.00001f) {
                float blendedR = (curR + tR) / newWeight;
                float blendedG = (curG + tG) / newWeight;
                float blendedB = (curB + tB) / newWeight;
                target[targetIdx] = 0xFF000000 | (clamp8(blendedR) << 16) | (clamp8(blendedG) << 8) | clamp8(blendedB);
            }
            weightMap[targetIdx] = newWeight;
        }
    }
}

// ---------------------------------------------------------------------------
// JNI Implementation
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
) {
    if (!assetManager || !paramPath || !binPath) {
        LOGE("loadModelNative: invalid arguments");
        return JNI_FALSE;
    }

    try {
        AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
        if (!mgr) {
            LOGE("loadModelNative: failed to obtain AAssetManager");
            return JNI_FALSE;
        }

        const char* param_str = env->GetStringUTFChars(paramPath, nullptr);
        const char* bin_str = env->GetStringUTFChars(binPath, nullptr);
        if (!param_str || !bin_str) {
            if (param_str) env->ReleaseStringUTFChars(paramPath, param_str);
            if (bin_str) env->ReleaseStringUTFChars(binPath, bin_str);
            LOGE("loadModelNative: failed to get string UTF chars");
            return JNI_FALSE;
        }

#if USE_NCNN
        std::lock_guard<std::mutex> lock(g_model_mutex);
        LOGI("loadModelNative: initializing NCNN with param: %s, bin: %s", param_str, bin_str);

#if NCNN_VULKAN
        if (!g_vulkan_initialized) {
            int v_ret = ncnn::create_gpu_instance();
            g_vulkan_initialized = (v_ret == 0);
            LOGI("loadModelNative: ncnn::create_gpu_instance() returned %d (vulkan_initialized=%d)", v_ret, g_vulkan_initialized);
        }

        g_has_vulkan = false;
        if (g_vulkan_initialized) {
            int gpu_count = ncnn::get_gpu_count();
            if (gpu_count > 0) {
                g_has_vulkan = true;
                LOGI("loadModelNative: Vulkan compute available (GPU count = %d)", gpu_count);
            } else {
                LOGI("loadModelNative: GPU count = 0, using CPU inference");
            }
        } else {
            LOGI("loadModelNative: Vulkan initialization failed or unsupported, using CPU inference");
        }
#else
        g_has_vulkan = false;
        LOGI("loadModelNative: Compiled without NCNN_VULKAN, using CPU inference");
#endif

        if (!g_net) {
            g_net = new ncnn::Net();
        } else {
            g_net->clear();
        }

        g_net->opt.use_vulkan_compute = g_has_vulkan;
#if NCNN_VULKAN
        if (g_has_vulkan) {
            int default_gpu = ncnn::get_default_gpu_index();
            if (default_gpu >= 0) {
                g_net->set_vulkan_device(default_gpu);
                LOGI("loadModelNative: Bound network to Vulkan device index: %d", default_gpu);
            }
        }
#endif
        g_net->opt.num_threads = ncnn::get_big_cpu_count();
        g_net->opt.use_fp16_packed = true;
        g_net->opt.use_fp16_storage = true;
        g_net->opt.use_fp16_arithmetic = true;
        g_net->opt.use_packing_layout = true;

        int ret_param = g_net->load_param(mgr, param_str);
        int ret_bin = g_net->load_model(mgr, bin_str);

        if (ret_param == 0 && ret_bin == 0) {
            g_model_loaded = true;
            LOGI("loadModelNative: Real-ESRGAN NCNN model loaded successfully!");
        } else {
            g_model_loaded = false;
            LOGE("loadModelNative: Failed to load model! ret_param=%d, ret_bin=%d", ret_param, ret_bin);
        }
#else
        LOGW("loadModelNative: arar_engine compiled without USE_NCNN. Mathematical fallback mode active.");
#endif

        env->ReleaseStringUTFChars(paramPath, param_str);
        env->ReleaseStringUTFChars(binPath, bin_str);

#if USE_NCNN
        return g_model_loaded ? JNI_TRUE : JNI_FALSE;
#else
        return JNI_FALSE;
#endif
    } catch (const std::exception& e) {
        LOGE("loadModelNative: C++ exception caught: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("loadModelNative: unknown C++ exception caught");
        return JNI_FALSE;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
) {
    if (!inPixels || width <= 0 || height <= 0) {
        return nullptr;
    }

    jint* src = env->GetIntArrayElements(inPixels, nullptr);
    if (!src) return nullptr;

    try {
        const uint32_t* srcU32 = reinterpret_cast<const uint32_t*>(src);

        // MODE 0: Super-Resolution (x4)
        if (mode == MODE_SUPER_RESOLUTION) {
            bool ncnn_success = false;
            int outW = width * 4;
            int outH = height * 4;
            std::vector<uint32_t> outBuffer(outW * outH);

#if USE_NCNN
            if (g_model_loaded && g_net) {
                std::lock_guard<std::mutex> lock(g_model_mutex);
                std::vector<unsigned char> rgbIn(width * height * 3);
                for (int i = 0; i < width * height; ++i) {
                    uint32_t c = srcU32[i];
                    rgbIn[i * 3 + 0] = (c >> 16) & 0xFF; // R
                    rgbIn[i * 3 + 1] = (c >> 8) & 0xFF;  // G
                    rgbIn[i * 3 + 2] = c & 0xFF;         // B
                }

                ncnn::Mat inMat = ncnn::Mat::from_pixels(rgbIn.data(), ncnn::Mat::PIXEL_RGB, width, height);
                const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
                const float meanVals[3] = {0.0f, 0.0f, 0.0f};
                inMat.substract_mean_normalize(meanVals, normVals);

                ncnn::Extractor ex = g_net->create_extractor();
                ex.input("data", inMat);

                ncnn::Mat outMat;
                int ret = ex.extract("output", outMat);
                if (ret == 0 && outMat.w > 0 && outMat.h > 0) {
                    outW = outMat.w;
                    outH = outMat.h;
                    outBuffer.resize(outW * outH);

                    std::vector<unsigned char> rgbOut(outW * outH * 3);
                    const float denormVals[3] = {255.0f, 255.0f, 255.0f};
                    outMat.substract_mean_normalize(meanVals, denormVals);
                    outMat.to_pixels(rgbOut.data(), ncnn::Mat::PIXEL_RGB);

                    for (int i = 0; i < outW * outH; ++i) {
                        uint32_t r = rgbOut[i * 3 + 0];
                        uint32_t g = rgbOut[i * 3 + 1];
                        uint32_t b = rgbOut[i * 3 + 2];
                        outBuffer[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    ncnn_success = true;
                } else {
                    LOGW("NCNN extraction returned code %d, falling back to bicubic super-resolution", ret);
                }
            }
#endif

            if (!ncnn_success) {
                float sharpen = strength > 0.0f ? strength : 0.8f;
                superResolutionFallback(srcU32, outBuffer.data(), width, height, 4, sharpen);
            }

            env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
            src = nullptr;

            jintArray result = env->NewIntArray(outW * outH);
            if (result) {
                env->SetIntArrayRegion(result, 0, outW * outH, reinterpret_cast<const jint*>(outBuffer.data()));
            }
            return result;
        }

        // Modes preserving dimensions (Denoise, Unsharp, Clarity)
        std::vector<uint32_t> outBuffer(width * height);

        if (mode == MODE_BILATERAL_DENOISE) {
            float sigma = strength > 0.0f ? strength * 10.0f : 15.0f;
            bilateralDenoise(srcU32, outBuffer.data(), width, height, 3.0f, sigma);
        } else if (mode == MODE_UNSHARP_MASK) {
            float amount = strength > 0.0f ? strength : 1.2f;
            unsharpMask(srcU32, outBuffer.data(), width, height, amount, 1.5f);
        } else if (mode == MODE_COMBINED_ENHANCE) {
            std::vector<uint32_t> temp(width * height);
            bilateralDenoise(srcU32, temp.data(), width, height, 2.0f, 10.0f);
            unsharpMask(temp.data(), outBuffer.data(), width, height, strength > 0.0f ? strength : 1.0f, 1.0f);
        } else {
            std::copy(srcU32, srcU32 + (width * height), outBuffer.begin());
        }

        env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        src = nullptr;

        jintArray result = env->NewIntArray(width * height);
        if (result) {
            env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(outBuffer.data()));
        }
        return result;
    } catch (const std::exception& e) {
        LOGE("processTileNative: C++ exception caught: %s", e.what());
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        return nullptr;
    } catch (...) {
        LOGE("processTileNative: unknown C++ exception caught");
        if (src) env->ReleaseIntArrayElements(inPixels, src, JNI_ABORT);
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
) {
    if (!bitmapIn || !bitmapOut) return JNI_FALSE;

    void* pixelsIn = nullptr;
    void* pixelsOut = nullptr;

    try {
        AndroidBitmapInfo infoIn, infoOut;
        if (AndroidBitmap_getInfo(env, bitmapIn, &infoIn) < 0 || AndroidBitmap_getInfo(env, bitmapOut, &infoOut) < 0) {
            return JNI_FALSE;
        }

        if (AndroidBitmap_lockPixels(env, bitmapIn, &pixelsIn) < 0) {
            return JNI_FALSE;
        }

        if (AndroidBitmap_lockPixels(env, bitmapOut, &pixelsOut) < 0) {
            AndroidBitmap_unlockPixels(env, bitmapIn);
            return JNI_FALSE;
        }

        const uint32_t* src = static_cast<const uint32_t*>(pixelsIn);
        uint32_t* dst = static_cast<uint32_t*>(pixelsOut);

        if (mode == MODE_UNSHARP_MASK) {
            unsharpMask(src, dst, infoIn.width, infoIn.height, strength > 0.0f ? strength : 1.2f, 1.5f);
        } else if (mode == MODE_BILATERAL_DENOISE) {
            bilateralDenoise(src, dst, infoIn.width, infoIn.height, 3.0f, strength > 0.0f ? strength * 10.0f : 15.0f);
        } else if (mode == MODE_SUPER_RESOLUTION) {
            superResolutionFallback(src, dst, infoIn.width, infoIn.height, 4, strength > 0.0f ? strength : 0.8f);
        } else {
            std::copy(src, src + (infoIn.width * infoIn.height), dst);
        }

        AndroidBitmap_unlockPixels(env, bitmapIn);
        AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("processBitmapNative: C++ exception caught: %s", e.what());
        if (pixelsIn) AndroidBitmap_unlockPixels(env, bitmapIn);
        if (pixelsOut) AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_FALSE;
    } catch (...) {
        LOGE("processBitmapNative: unknown C++ exception caught");
        if (pixelsIn) AndroidBitmap_unlockPixels(env, bitmapIn);
        if (pixelsOut) AndroidBitmap_unlockPixels(env, bitmapOut);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jint tileW,
    jint tileH,
    jint dstX,
    jint dstY,
    jintArray targetPixels,
    jint targetW,
    jint targetH
) {
    if (!tilePixels || !targetPixels || tileW <= 0 || tileH <= 0 || targetW <= 0 || targetH <= 0) {
        return JNI_FALSE;
    }

    jint* tile = nullptr;
    jint* target = nullptr;

    try {
        tile = env->GetIntArrayElements(tilePixels, nullptr);
        target = env->GetIntArrayElements(targetPixels, nullptr);
        if (!tile || !target) {
            if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
            if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
            return JNI_FALSE;
        }

        std::vector<float> weightMap(targetW * targetH, 1.0f);
        cosineTileBlend(reinterpret_cast<const uint32_t*>(tile), tileW, tileH, dstX, dstY,
                        reinterpret_cast<uint32_t*>(target), weightMap.data(), targetW, targetH);

        env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        env->ReleaseIntArrayElements(targetPixels, target, 0);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("blendTileNative: C++ exception caught: %s", e.what());
        if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
        return JNI_FALSE;
    } catch (...) {
        LOGE("blendTileNative: unknown C++ exception caught");
        if (tile) env->ReleaseIntArrayElements(tilePixels, tile, JNI_ABORT);
        if (target) env->ReleaseIntArrayElements(targetPixels, target, JNI_ABORT);
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// Backward-compatible wrappers for com.example.engine.ImageEnhancerNative
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(env, thiz, assetManager, paramPath, binPath);
}

JNIEXPORT jintArray JNICALL
Java_com_example_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(env, thiz, inPixels, width, height, mode, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(env, thiz, bitmapIn, bitmapOut, mode, strength);
}

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jint tileW,
    jint tileH,
    jint dstX,
    jint dstY,
    jintArray targetPixels,
    jint targetW,
    jint targetH
) {
    return Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(env, thiz, tilePixels, tileW, tileH, dstX, dstY, targetPixels, targetW, targetH);
}

// ---------------------------------------------------------------------------
// JNI Lifecycle hooks
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: initializing arar_engine native library");
    try {
#if USE_NCNN && NCNN_VULKAN
        if (!g_vulkan_initialized) {
            int v_ret = ncnn::create_gpu_instance();
            g_vulkan_initialized = (v_ret == 0);
            LOGI("JNI_OnLoad: ncnn::create_gpu_instance() returned %d (vulkan_initialized=%d)", v_ret, g_vulkan_initialized);
        }
#endif
    } catch (...) {
        LOGE("JNI_OnLoad: exception caught during initialization");
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnUnload: cleaning up arar_engine native library");
    try {
#if USE_NCNN
        if (g_net) {
            delete g_net;
            g_net = nullptr;
        }
#if NCNN_VULKAN
        if (g_vulkan_initialized) {
            ncnn::destroy_gpu_instance();
            g_vulkan_initialized = false;
            LOGI("JNI_OnUnload: ncnn::destroy_gpu_instance() executed");
        }
#endif
#endif
    } catch (...) {
        LOGE("JNI_OnUnload: exception caught during cleanup");
    }
}

} // extern "C"
