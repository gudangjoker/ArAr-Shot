# ProGuard rules for ArAr Shot C++ Native Engine & JNI Bridge

# Keep JNI Native Bridge classes & methods
-keep class com.example.engine.ImageEnhancerNative {
    *;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Model & Engine Data Structures
-keep class com.example.engine.** {
    *;
}

# Keep NCNN internal JNI references if any
-keep class com.tencent.ncnn.** {
    *;
}
