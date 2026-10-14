# 📷 ArAr Shot

[![Android Build](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml/badge.svg)](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml)
![Android Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue)
![Android Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-green)
![Release](https://img.shields.io/badge/Release-v1.0.0-purple)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Telemetry-success)

Aplikasi Android mandiri bertenaga **Real-ESRGAN AI** dan **C++ Native Vulkan Engine** untuk meningkatkan resolusi foto (4x Super-Resolution), pengurangan noise (*bilateral denoising*), dan penajaman gambar (*unsharp masking*) secara instan langsung di chip perangkat.

Dirancang untuk **pemakaian pribadi (Personal Release)** dengan jaminan privasi 100% tanpa telemetri atau koneksi internet.

---

## 🌟 Fitur Utama

- **🚀 AI 4x Super-Resolution**: Didukung model syaraf `realesr-general-x4v3` yang dioptimasi via Tencent **NCNN** dengan akselerasi GPU Vulkan compute.
- **⚡ Native C++ Processing**: Engine `arar_engine` berbasis C++17 untuk komputasi filter piksel berkecepatan tinggi dengan auto-fallback matematis.
- **🛡️ 100% Offline & Anti-Pelacak**: Tanpa izin internet (`android.permission.INTERNET` tidak disertakan), tanpa Firebase, tanpa Google Analytics.
- **📱 Izin Akses Hardware**: Integrasi izin akses Kamera, Mikrofon, dan Penyimpanan dengan auto-prompt runtime di Jetpack Compose UI.
- **🔑 Pre-Signed & Sideload Ready**: APK rilis otomatis ditandatangani dengan keystore aman sehingga dapat diinstal langsung di smartphone tanpa pesan error.

---

## 📁 Struktur Direktori

```
ArAr Shot/
├── .github/workflows/
│   └── android-build.yml           # Workflow CI/CD Cloud Build GitHub Actions
├── app/
│   ├── src/main/
│   │   ├── assets/models/          # Bobot Model Real-ESRGAN NCNN (.param & .bin)
│   │   ├── cpp/                    # Sumber Native C++ & CMakeLists.txt
│   │   │   ├── CMakeLists.txt      # Build script CMake dengan deteksi dinamis NCNN
│   │   │   ├── native_enhancer.cpp # Implementasi NCNN inference & fallback
│   │   │   └── native_enhancer.h   # Header dan prototype JNI bridge
│   │   ├── java/com/example/       # Kode Kotlin & Jetpack Compose UI
│   │   │   ├── MainActivity.kt     # Activity utama + runtime permissions
│   │   │   └── engine/
│   │   │       └── ImageEnhancerNative.kt # JNI Kotlin Bridge
│   │   ├── res/                    # Resource Android (Icons, Themes, Strings)
│   │   └── AndroidManifest.xml     # Manifest aplikasi & izin hardware
│   ├── build.gradle.kts            # Konfigurasi modul app (NDK, CMake, Signing)
│   └── proguard-rules.pro          # Aturan proteksi JNI untuk release build
├── gradle/
│   ├── libs.versions.toml          # Version Catalog Gradle
│   └── wrapper/                    # Gradle Wrapper 8.9
├── setup_ncnn.sh                   # Script helper download NCNN SDK (Linux/CI)
├── setup_ncnn.ps1                  # Script helper download NCNN SDK (Windows)
├── RELEASE_NOTES.md                # Catatan & informasi rilis versi 1.0.0
└── README.md                       # Dokumentasi repositori
```

---

## 🛠️ Kompilasi Lokal

### Prasyarat
- **JDK 17** (Temurin / Adoptium)
- **Android SDK & NDK** (API 34, NDK r25+)
- **CMake** 3.22.1+

### Langkah Build
1. Jalankan script untuk mengekstrak NCNN SDK (opsional, fallback otomatis aktif jika dilewati):
   ```bash
   # Di Linux / macOS:
   ./setup_ncnn.sh

   # Di Windows PowerShell:
   .\setup_ncnn.ps1
   ```

2. Kompilasi APK Signed:
   ```bash
   # Di Linux / macOS:
   ./gradlew assembleRelease

   # Di Windows PowerShell:
   .\gradlew.bat assembleRelease
   ```

File APK siap pakai akan tersedia di `app/build/outputs/apk/release/app-release.apk`.

---

## ☁️ Cloud Build Otomatis (GitHub Actions)

Setiap ada `push` ke branch `main`, GitHub Actions secara otomatis:
1. Menyiapkan environment Ubuntu, JDK 17, Android SDK & NDK.
2. Mengunduh NCNN Vulkan SDK dan memverifikasi model AI.
3. Menandatangani dan mengompilasi APK rilis.
4. Mengunggah file `.apk` ke tab **Actions > Artifacts**.

Unduh rilis terbaru langsung dari:
👉 **[GitHub Actions Runs](https://github.com/gudangjoker/ArAr-Shot/actions)**

---

## 📄 Informasi Rilis
Untuk catatan perubahan dan spesifikasi mendalam, lihat [RELEASE_NOTES.md](RELEASE_NOTES.md).
