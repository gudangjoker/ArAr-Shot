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

// Mode definitions
#define MODE_SUPER_RESOLUTION  0
#define MODE_BILATERAL_DENOISE 1
#define MODE_UNSHARP_MASK      2
#define MODE_COMBINED_ENHANCE  3
#define MODE_FACE_RESTORE      4

struct DetectedFace {
    float x;
    float y;
    float w;
    float h;
    float score;
    float landmarks[10]; // 5 points: (x0,y0), (x1,y1), (x2,y2), (x3,y3), (x4,y4)
};

// Native mathematical and hybrid AI enhancement functions
void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius);
void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma);
void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount);
void cosineTileBlendWithOverlap(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                                uint32_t* target, float* weightMap, int targetW, int targetH, int overlapPx);
void cosineTileBlend(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                     uint32_t* target, float* weightMap, int targetW, int targetH);

// Remini-Level Face Detection & Blind Face Restoration Pipeline
std::vector<DetectedFace> detectFacesInternal(const uint32_t* src, int width, int height);
void restoreFacesRemini(const uint32_t* src, uint32_t* dst, int width, int height, float fidelityStrength);

// Guided Alpha Matting & Monocular 3D Depth Estimation
void extractAlphaMatteInternal(const uint32_t* src, const float* coarseMask, float* outAlpha, int width, int height);
void estimateDepthMapInternal(const uint32_t* src, const float* subjectMask, float* outDepth, int width, int height);

// 7 Viral AI Engine Native Algorithms
void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius);
void depthBokeh(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth);
void relighting3D(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height,
                  float lightX, float lightY, float lightZ,
                  float lightR, float lightG, float lightB, float intensity);
void vintageHalation(const uint32_t* src, uint32_t* dst, int width, int height,
                     float halationStrength, float grainAmount, float vignetteStrength);
void animeCelShade(const uint32_t* src, uint32_t* dst, int width, int height, int quantLevels, float edgeThreshold);
void colorizeGrayscale(const uint32_t* src, uint32_t* dst, int width, int height, float saturation);
void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int outlineWidth, uint32_t outlineColor,
                         int shadowOffsetX, int shadowOffsetY, float shadowBlur, uint32_t bgColor);

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved);

// JNI signatures for com.ararshot.app.engine.ImageEnhancerNative
JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
);

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_initAllModelsNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inputPixels,
    jint tileW,
    jint tileH,
    jint mode,
    jfloat strength
);

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject inputBitmap,
    jobject outputBitmap,
    jint mode,
    jfloat strength
);

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jintArray targetPixels,
    jint tileW,
    jint tileH,
    jint targetW,
    jint targetH,
    jint offsetX,
    jint offsetY,
    jint overlapPx
);

JNIEXPORT void JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_blendTileWithWeightNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jintArray targetPixels,
    jfloatArray weightMap,
    jint tileW,
    jint tileH,
    jint targetW,
    jint targetH,
    jint offsetX,
    jint offsetY,
    jint overlapPx
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_detectFacesNative(
    JNIEnv* env,
    jobject thiz,
    jintArray srcPixels,
    jint width,
    jint height
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_restoreFacesNative(
    JNIEnv* env,
    jobject thiz,
    jintArray srcPixels,
    jint width,
    jint height,
    jfloat strength
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_extractAlphaMatteNative(
    JNIEnv* env,
    jobject thiz,
    jintArray srcPixels,
    jfloatArray coarseMask,
    jint width,
    jint height
);

JNIEXPORT jfloatArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_estimateDepthNative(
    JNIEnv* env,
    jobject thiz,
    jintArray srcPixels,
    jfloatArray subjectMask,
    jint width,
    jint height
);

// JNI signatures for 7 Viral AI Engine features (com.ararshot.app.engine.ImageEnhancerNative)
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
Java_com_ararshot_app_engine_ImageEnhancerNative_applyHalationNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jfloat halationStrength, jfloat grainAmount, jfloat vignetteStrength
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyAnimeFilterNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jint quantLevels, jfloat edgeThreshold
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_colorizeImageNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jint width, jint height, jfloat saturation
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_applyStudioShadowOutlineNative(
    JNIEnv* env, jobject thiz, jintArray srcPixels, jfloatArray mask, jint width, jint height,
    jint outlineWidth, jint outlineColor, jint shadowOffsetX, jint shadowOffsetY, jfloat shadowBlur, jint bgColor
);

// Backward-compatible signatures for com.example.engine.ImageEnhancerNative
JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_loadModelNative(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring paramPath,
    jstring binPath
);

JNIEXPORT jintArray JNICALL
Java_com_example_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inputPixels,
    jint tileW,
    jint tileH,
    jint mode,
    jfloat strength
);

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject inputBitmap,
    jobject outputBitmap,
    jint mode,
    jfloat strength
);

JNIEXPORT void JNICALL
Java_com_example_engine_ImageEnhancerNative_blendTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray tilePixels,
    jintArray targetPixels,
    jint tileW,
    jint tileH,
    jint targetW,
    jint targetH,
    jint offsetX,
    jint offsetY,
    jint overlapPx
);

#ifdef __cplusplus
}
#endif
