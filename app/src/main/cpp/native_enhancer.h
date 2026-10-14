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
#define MODE_SUPER_RESOLUTION 0
#define MODE_BILATERAL_DENOISE 1
#define MODE_UNSHARP_MASK      2
#define MODE_COMBINED_ENHANCE  3

// Native mathematical fallback and enhancement functions
void unsharpMask(const uint32_t* src, uint32_t* dst, int width, int height, float amount, float radius);
void bilateralDenoise(const uint32_t* src, uint32_t* dst, int width, int height, float spatialSigma, float colorSigma);
void superResolutionFallback(const uint32_t* src, uint32_t* dst, int srcW, int srcH, int scale, float sharpenAmount);
void cosineTileBlend(const uint32_t* tile, int tileW, int tileH, int dstX, int dstY,
                     uint32_t* target, float* weightMap, int targetW, int targetH);

// 7 Viral AI Engine Native Algorithms
void teleaInpaint(const uint32_t* src, const int32_t* mask, uint32_t* dst, int width, int height, int radius);
void depthBokeh(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height, float maxBlurRadius, float focusDepth);
void relighting3D(const uint32_t* src, const float* depthMask, uint32_t* dst, int width, int height,
                  float lightX, float lightY, float lightZ,
                  float lightR, float lightG, float lightB, float intensity);
void animeCelShade(const uint32_t* src, uint32_t* dst, int width, int height, int radius, float edgeStrength, float vibrancy);
void vintageHalation(const uint32_t* src, uint32_t* dst, int width, int height,
                     float halationRadius, float grainStrength, float chromaticShift, float warmth);
void colorizeGrayscale(const uint32_t* src, const float* personMask, uint32_t* dst, int width, int height,
                       float skinWarmth, float skyCyan);
void studioShadowOutline(const uint32_t* src, const float* mask, uint32_t* dst, int width, int height,
                         int shadowDx, int shadowDy, int shadowRadius, float shadowAlpha,
                         int outlineWidth, uint32_t outlineColor);

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

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processTileNative(
    JNIEnv* env,
    jobject thiz,
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
);

JNIEXPORT jboolean JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
);

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
);

// JNI signatures for 7 Viral AI Engine features (com.ararshot.app.engine.ImageEnhancerNative)
JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_inpaintNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jintArray inMask, jint width, jint height, jint radius
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_depthBokehNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray depthMask, jint width, jint height, jfloat blurRadius, jfloat focusDepth
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_relighting3DNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray depthMask, jint width, jint height,
    jfloat lightX, jfloat lightY, jfloat lightZ, jfloat lightR, jfloat lightG, jfloat lightB, jfloat intensity
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_animeCelShadeNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jint width, jint height, jint radius, jfloat edgeStrength, jfloat vibrancy
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_vintageHalationNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jint width, jint height, jfloat halationRadius, jfloat grainStrength, jfloat chromaticShift, jfloat warmth
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_colorizeGrayscaleNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray personMask, jint width, jint height, jfloat skinWarmth, jfloat skyCyan
);

JNIEXPORT jintArray JNICALL
Java_com_ararshot_app_engine_ImageEnhancerNative_studioShadowOutlineNative(
    JNIEnv* env, jobject thiz, jintArray inPixels, jfloatArray mask, jint width, jint height,
    jint shadowDx, jint shadowDy, jint shadowRadius, jfloat shadowAlpha, jint outlineWidth, jint outlineColor
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
    jintArray inPixels,
    jint width,
    jint height,
    jint mode,
    jfloat strength
);

JNIEXPORT jboolean JNICALL
Java_com_example_engine_ImageEnhancerNative_processBitmapNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmapIn,
    jobject bitmapOut,
    jint mode,
    jfloat strength
);

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
);

#ifdef __cplusplus
}
#endif
