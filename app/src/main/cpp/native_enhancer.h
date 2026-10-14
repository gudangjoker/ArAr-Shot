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

#ifdef __cplusplus
extern "C" {
#endif

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
