# 📸 ArAr Shot v.1.0 — Release Notes

## Versi 1.0 (ArAr Shot v.1.0 Personal Offline Release)
**Tanggal Rilis**: 14 Oktober 2026  
**Package Name**: `com.ararshot.app`  
**Tipe Rilis**: Edisi Pribadi (Offline Standalone Build)  
**Status Tanda Tangan**: Signed (Valid RSA 2048-bit Keystore)

---

### ✨ Ringkasan Rilis
ArAr Shot v.1.0 adalah aplikasi peningkatan kualitas foto (*photo enhancer & super-resolution*) bertenaga AI lokal yang dirancang khusus untuk pemakaian pribadi dengan fokus utama pada **kecepatan pemrosesan hardware lokal, privasi 100%, dan kemudahan instalasi**. Dilengkapi dengan logo & ikon kamera futuristik gradien terbaru.

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
| **Versi Aplikasi** | `1.0` (Version Code: `1`) |
| **Target SDK** | Android 14 (API Level 34) |
| **Minimum SDK** | Android 7.0 Nougat (API Level 24) |
| **Arsitektur CPU (ABI)** | `arm64-v8a` (64-bit modern), `armeabi-v7a` (32-bit legacy) |
| **Ukuran APK Rilis** | **~21.8 MB** (Semua model AI dan native library sudah di dalam APK) |
| **UI Framework** | Jetpack Compose + Material 3 Design |
| **Build System** | Gradle 8.9 + AGP 8.6.1 + CMake 3.22.1 + NDK 25/26 |

---

### 📲 Panduan Instalasi di Ponsel Android

1. Unduh file `ArArShot-Personal-Release-APK` dari tab **Actions > Artifacts** atau halaman **Releases**.
2. Ekstrak file zip hasil unduhan untuk mendapatkan file `app-release.apk`.
3. Pindahkan file `.apk` ke ponsel Android Anda (via kabel data USB, WhatsApp, atau Telegram).
4. Tap file `.apk` di ponsel Anda untuk memulai proses pemasangan:
   - Jika muncul peringatan *"Install from unknown sources"*, pilih **Izinkan (Allow)**.
5. Selesai! Aplikasi langsung dapat digunakan secara penuh tanpa koneksi internet.
