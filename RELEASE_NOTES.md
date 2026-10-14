# 📸 ArAr Shot v.1.0.3 — Release Notes

## Versi 1.0.3 (ArAr Shot v.1.0.3 Interactive Photo Enhancer Release)
**Tanggal Rilis**: 14 Oktober 2026  
**Package Name**: `com.ararshot.app`  
**Tipe Rilis**: Edisi Pribadi (Offline Standalone Build)  
**Status Tanda Tangan**: Signed (Valid RSA 2048-bit Keystore)

---

### ✨ Ringkasan Rilis
ArAr Shot v.1.0.3 menghadirkan **Antarmuka Interaktif Lengkap Peningkatan Foto**:
1. **Pemilih Foto (Gallery Picker)**: Pilih foto mana pun dari galeri perangkat Anda secara langsung.
2. **Kamera Terintegrasi**: Ambil foto baru langsung dari dalam aplikasi.
3. **Multi-Mode AI & Native Enhancer**:
   - ✨ **4x Super-Resolution**: Meningkatkan resolusi 4x lipat menggunakan model AI Real-ESRGAN NCNN Vulkan.
   - 🚀 **Ultra Enhance**: Kombinasi Bilateral Denoise dan 4x Super-Resolution.
   - 🔍 **Pertajam Detail**: Filter Unsharp Masking berpresisi tinggi.
   - 🧼 **Bersihkan Noise**: Algoritma Bilateral Denoising pereduksi bintik foto.
4. **Perbandingan Langsung (Before & After Tabs)**: Bandingkan foto asli vs foto hasil peningkatan AI dengan satu sentuhan.
5. **Simpan ke Galeri**: Simpan hasil foto beresolusi tinggi langsung ke folder `Pictures/ArArShot` di penyimpanan HP.
6. **Indikator Proses Visual**: Menampilkan animasi pemrosesan aktif saat AI sedang merekonstruksi piksel foto.

---

### 🛠️ Perbaikan Utama (Penyebab Force Close Teratasi)
1. **Penyebab Utama: Plugin Kompilasi Kotlin Android (`org.jetbrains.kotlin.android`)**:
   - Sebelumnya, plugin Kotlin Android belum terpasang di Gradle sehingga kode Kotlin (`MainActivity.kt` & `ImageEnhancerNative.kt`) tidak terkompilasi ke dalam file DEX APK. Hal ini menyebabkan crash instan `ClassNotFoundException` saat aplikasi dibuka dari launcher.
   - Sekarang plugin Kotlin Android 2.0.20 aktif, dan seluruh kelas aplikasi terverifikasi 100% berada di dalam `classes.dex`.
2. **Inisialisasi Vulkan NCNN (`ncnn::create_gpu_instance`)**:
   - Menambahkan pemanggilan `ncnn::create_gpu_instance()` pada `JNI_OnLoad` dan `loadModelNative` untuk mencegah native SIGSEGV crash pada smartphone dengan GPU Vulkan.
3. **Pemuatan Library Native C++ Bersifat Lazy**:
   - Menghilangkan pemanggilan `System.loadLibrary` pada blok `init` singleton agar tidak memicu `ExceptionInInitializerError` saat startup. Native library dimuat secara aman dan sinkron saat dibutuhkan.
4. **Ekstraksi Native Library APK (`useLegacyPackaging = true`)**:
   - Mengaktifkan `packaging.jniLibs.useLegacyPackaging = true` agar file `libncnn.so` dan `libarar_engine.so` diekstrak langsung ke storage aplikasi saat instalasi, menghindari kegagalan dynamic linker pada ROM Samsung One UI, Xiaomi HyperOS/MIUI, dan Oppo/Vivo.
5. **Kompatibilitas Tema & Activity**:
   - Mengintegrasikan `androidx.appcompat:appcompat:1.7.0`, menyelaraskan tema dengan `Theme.AppCompat.DayNight.NoActionBar`, dan menambahkan crash handler global ke `crash_log.txt`.

---

### 🚀 Fitur Utama

1. **4x Super-Resolution AI (Real-ESRGAN NCNN Vulkan)**:
   - Menggunakan bobot model `realesr-general-x4v3` (~4.8 MB) yang telah dikompilasi ke format NCNN.
   - Akselerasi GPU Vulkan compute (`opt.use_vulkan_compute = true`) untuk pemrosesan cepat pada GPU smartphone.
   - Eksekusi hemat memori dengan format FP16 (`use_fp16_packed`, `use_fp16_arithmetic`).

2. **Native C++ Image Engine (`libarar_engine.so`)**:
   - **Bilateral Denoise**: Menghilangkan noise/bintik pada foto dengan tetap mempertahankan ketajaman garis tepi (*edge-preserving*).
   - **Unsharp Mask Sharpening**: Algoritma Gaussian blur difference untuk mempertajam detail tekstur foto.
   - **2D Cosine Hann Tile Blending**: Menghilangkan garis batas sambungan (*seam lines*) antar-tile saat memproses gambar beresolusi tinggi.
   - **Safe Fallback**: Jika perangkat tidak mendukung Vulkan GPU, engine otomatis beralih ke komputasi CPU multi-threaded dan algoritma matematis native tanpa crash.

3. **100% Offline & Privasi Terjaga**:
   - **Bebas Telemetri**: Tidak ada pelacak Firebase, Google Analytics, atau Crashlytics.
   - **Tanpa Izin Internet**: Aplikasi sama sekali tidak meminta izin `android.permission.INTERNET`. Foto pengguna tidak pernah keluar dari perangkat.
   - **Backup Awan Dinonaktifkan**: Pengaturan `android:allowBackup="false"` mencegah foto di-upload otomatis ke Google Cloud Backup.

4. **Izin Akses Hardware Terintegrasi**:
   - Dukungan akses hardware smartphone: **Kamera**, **Mikrofon (Audio)**, dan **Penyimpanan (Media/Foto)**.
   - Dialog izin interaktif (*Runtime Permission*) pada Jetpack Compose UI yang otomatis muncul saat aplikasi dibuka.

---

### 📋 Spesifikasi Teknis

| Parameter | Spesifikasi |
| :--- | :--- |
| **Versi Aplikasi** | `1.0.3` (Version Code: `4`) |
| **Target SDK** | Android 14 (API Level 34) |
| **Minimum SDK** | Android 7.0 Nougat (API Level 24) |
| **Arsitektur CPU (ABI)** | `arm64-v8a` (64-bit modern), `armeabi-v7a` (32-bit legacy), `x86_64` |
| **Ukuran APK Rilis** | **~33.4 MB** (Semua model AI dan native library sudah di dalam APK) |
| **UI Framework** | Jetpack Compose + Material 3 Design |
| **Build System** | Gradle 8.9 + AGP 8.6.1 + CMake 3.22.1 + NDK 25/26 |

---

### 📲 Panduan Instalasi di Ponsel Android

1. Unduh berkas **`ArAr-Shot-v.1.0.3.apk`** (atau `ArAr-Shot-v.1.0.3-release.apk`) langsung dari bagian **Assets** di halaman GitHub Releases ini.
2. Buka/Pasang berkas `ArAr-Shot-v.1.0.3.apk` di ponsel Anda.
3. Langsung pilih foto dari galeri atau foto kamera untuk memulai peningkatan kualitas foto dengan AI!
