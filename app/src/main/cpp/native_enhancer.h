#pragma once

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <memory>
#include <cstdint>
#include <cstring>
#include <mutex>

#define TAG "ArArEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if USE_NCNN
#include <net.h>
#include <cpu.h>
#if NCNN_VULKAN
#include <gpu.h>
#endif
#endif

// Enhance mode constants
#define MODE_SUPER_RESOLUTION  0
#define MODE_BILATERAL_DENOISE 1
#define MODE_UNSHARP_MASK      2
#define MODE_COMBINED_ENHANCE  3
#define MODE_FACE_RESTORE      4
#define MODE_DENOISE_ONLY      5

struct DetectedFace {
    float x;
    float y;
    float w;
    float h;
    float score;
    float landmarks[10]; // 5 points: (lEye, rEye, nose, lMouth, rMouth)
};

// Packed Linear-Light Color & Filter Pipeline Parameters (42 floats)
// [0]  exposure (-2..+2 EV)
// [1]  contrast (-1..+1)
// [2]  highlights (-1..+1)
// [3]  shadows (-1..+1)
// [4]  whites (-1..+1)
// [5]  blacks (-1..+1)
// [6]  temp (-1..+1)
// [7]  tint (-1..+1)
// [8]  vibrance (-1..+1)
// [9]  saturation (-1..+1)
// [10] clarity (-1..+1)
// [11] sharpen (0..1)
// [12] fade (0..1)
// [13] vignette (-1..+1)
// [14] grain (0..1)
// [15] halation (0..1)
// [16] presetId (0 = None, 1..12 = Curated looks)
// [17] presetStrength (0..1)
// [18..25] hslHue[8] (-1..+1)
// [26..33] hslSat[8] (-1..+1)
// [34..41] hslLum[8] (-1..+1)
constexpr int COLOR_PARAMS_COUNT = 42;

// Core C++17 image processing functions (ARGB_8888: 0xAARRGGBB)
void applyLinearColorPipeline(const uint32_t* src, uint32_t* dst, int width, int height, const float* params);
void analyzeAutoEnhance(const uint32_t* src, int width, int height, float* outParams);

void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius);
void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma);
void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount);
void cosineTileBlendWithOverlap(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                                uint32_t* target, float* weightMap, int targetW, int targetH, int overlapPx);

std::vector<DetectedFace> detectFacesInternal(const uint32_t* src, int width, int height);
void restoreFacesNeural(const uint32_t* src, uint32_t* dst, int width, int height, float fidelityStrength);

void extractAlphaMatteInternal(const uint32_t* src, const float* coarseMask, float* outAlpha, int width, int height, bool isPortraitHint);
void estimateDepthMapInternal(const uint32_t* src, const float* subjectMask, float* outDepth, int width, int height);

void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius);
void depthBokehLinear(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth);
void relighting3D(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height,
                  float lightX, float lightY, float lightZ,
                  float lightR, float lightG, float lightB, float intensity);
void animeStylize(const uint32_t* src, uint32_t* dst, int width, int height, int styleMode, float strength);
void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int outlineWidth, uint32_t outlineColor,
                         int shadowOffsetX, int shadowOffsetY, float shadowBlur, float shadowAlpha, uint32_t bgColor);

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved);

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_initAssetManagerNative(
    JNIEnv* env, jobject thiz, jobject assetManager
);

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_releaseModelsNative(
    JNIEnv* env, jobject thiz
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyLinearColorNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jfloatArray params
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_analyzeAutoEnhanceNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env, jobject thiz, jintArray inputPixels, jint tileW, jint tileH, jint mode, jfloat strength
);

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env, jobject thiz, jobject inputBitmap, jobject outputBitmap, jint mode, jfloat strength
);

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileWithWeightNative(
    JNIEnv* env, jobject thiz, jintArray tilePixels, jintArray targetPixels, jfloatArray weightMap,
    jint tileW, jint tileH, jint targetW, jint targetH, jint offsetX, jint offsetY, jint overlapPx
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_detectFacesNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_restoreFacesNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jfloat strength
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_extractAlphaMatteNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray coarseMask, jint width, jint height, jboolean isPortraitHint
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_estimateDepthNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray subjectMask, jint width, jint height
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_inpaintNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jintArray maskPixels, jint width, jint height, jint radius
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyBokehNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray depthMask, jint width, jint height, jfloat blurRadius, jfloat focusDepth
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyRelightingNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray depthMask, jint width, jint height,
    jfloat lightX, jfloat lightY, jfloat lightZ, jfloat lightR, jfloat lightG, jfloat lightB, jfloat intensity
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyAnimeStylizeNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jint styleMode, jfloat strength
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyStudioShadowOutlineNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray mask, jint width, jint height,
    jint outlineWidth, jint outlineColor, jint shadowOffsetX, jint shadowOffsetY,
    jfloat shadowBlur, jfloat shadowAlpha, jint bgColor
);

#ifdef __cplusplus
}
#endif
