# 📸 ArAr Shot Pro v.1.0.4 — Release Notes

## Versi 1.0.4 (ArAr Shot Pro Full Studio Suite)
**Tanggal Rilis**: 14 Oktober 2026  
**Package Name**: `com.ararshot.app`  
**Tipe Rilis**: Edisi Pribadi (Offline Standalone Studio Suite)  
**Status Tanda Tangan**: Signed (Valid RSA 2048-bit Keystore)

---

### ✨ Ringkasan Rilis Penuh (Full Production Suite)
ArAr Shot Pro v.1.0.4 mengimplementasikan **seluruh blueprint arsitektur profesional lengkap**:
1. **HomeScreen Studio Hub**:
   - 🚀 **AI Super-Resolution 4X**: Pemrosesan Real-ESRGAN NCNN Vulkan dengan algoritma dynamic tiling anti-OOM.
   - 🎨 **Pro Color Studio & Filter**: Pengaturan presisi Exposure, Contrast, Saturation, Temperature, dan Vignette secara real-time.
   - ✂️ **Crop & Transform Studio**: Rotasi presisi 90°, Flip horisontal/vertikal, dan pemotongan rasio aspek (1:1 Square, 4:5 Portrait, 16:9 Landscape, 9:16 Story).
   - 🖼️ **Collage Studio Grid**: Penggabungan 2 hingga 4 foto dengan template grid, kontrol margin (spacing), dan sudut membulat (corner radius).
2. **EditorScreen dengan Interactive Before/After Split Slider**:
   - Slider geser interaktif untuk membandingkan foto asli vs hasil peningkatan AI secara berdampingan dengan gesture drag real-time.
   - 4 Tab modular: `[AI Enhance]`, `[Color Grade]`, `[Transform]`, `[Export]`.
3. **TileProcessor & Anti-OOM Architecture**:
   - Algoritma pemecahan tile dengan overlap margin untuk mencegah artefak garis sambungan (*seam lines*).
   - Cosine Hann window tile blending via C++ NDK engine.
   - Progress bar dinamis (persentase dan nomor tile).
4. **100% Offline & Privasi Mutlak**:
   - Bebas pelacak dan tanpa memerlukan koneksi internet apa pun.

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
| **Versi Aplikasi** | `1.0.4` (Version Code: `5`) |
| **Target SDK** | Android 14 (API Level 34) |
| **Minimum SDK** | Android 7.0 Nougat (API Level 24) |
| **Arsitektur CPU (ABI)** | `arm64-v8a` (64-bit modern), `armeabi-v7a` (32-bit legacy), `x86_64` |
| **Ukuran APK Rilis** | **~33.5 MB** (Semua model AI dan native library sudah di dalam APK) |
| **UI Framework** | Jetpack Compose + Material 3 Design |
| **Build System** | Gradle 8.9 + AGP 8.6.1 + CMake 3.22.1 + NDK 25/26 |

---

### 📲 Panduan Instalasi di Ponsel Android

1. Unduh berkas **`ArAr-Shot-v.1.0.4.apk`** (atau `ArAr-Shot-v.1.0.4-release.apk`) langsung dari bagian **Assets** di halaman GitHub Releases ini.
2. Buka/Pasang berkas `ArAr-Shot-v.1.0.4.apk` di ponsel Anda.
3. Nikmati seluruh fitur lengkap: Quick AI Enhance 4X, Pro Color Grading, Crop & Transform, dan Collage Studio Grid!
