# ProGuard rules for ArAr Shot C++ Native Engine & JNI Bridge

# Keep JNI Native Bridge classes & methods for com.ararshot.app
-keep class com.ararshot.app.engine.ImageEnhancerNative {
    *;
}

-keep class com.ararshot.app.** {
    *;
}

# Keep any native methods
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
