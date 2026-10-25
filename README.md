# 📷 ArAr Shot

[![Android Build](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml/badge.svg)](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml)
![Android Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue)
![Android Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-green)
![Release](https://img.shields.io/badge/Release-v2.0.1-purple)
![Privasi](https://img.shields.io/badge/Privasi-100%25%20Offline-success)

Aplikasi editor foto Android yang berjalan 100% secara luring (tanpa internet) menggunakan pemrosesan native C++17 dan model neural NCNN on-device.

---

## 📱 Fitur Utama

- **Restorasi Wajah & Resolusi 4X**: Memperjelas area wajah pada foto buram menggunakan deteksi wajah SCRFD dan perbesaran Real-ESRGAN.
- **Hapus Objek**: Menghapus objek pengganggu atau noda pada foto menggunakan kuas usap inpainting.
- **Efek Bokeh & Pencahayaan 3D**: Menghasilkan efek blur latar belakang dan simulasi pencahayaan berbasis estimasi kedalaman (Depth Anything).
- **Hapus Latar Belakang**: Memisahkan subjek dari latar belakang dengan deteksi MODNet dan ML Kit, mendukung ekspor PNG transparan, ganti warna studio, bayangan 3D, atau garis tepi stiker.
- **Pewarnaan Foto Lawas**: Memberikan warna alami pada foto hitam-putih kenangan pada ruang warna CIE LAB.
- **Gaya Anime**: Mengubah potret menjadi ilustrasi gaya anime menggunakan model AnimeGANv2.
- **Pasfoto Resmi**: Pengaturan pasfoto ukuran standar (2x3, 3x4, 4x6, visa) dengan latar belakang merah/biru resmi serta penyusunan lembar cetak 4R.
- **Kamera Lawas CCD**: Filter bernuansa kamera saku 2000-an dengan pendaran cahaya (halation), bintik film analog, dan cap tanggal.
- **Teks & Tipografi**: Menambahkan tulisan dengan pilihan gaya (neon, bayangan, garis tepi stiker) serta fitur teks di belakang subjek.
- **Studio Kolase**: Menggabungkan 2 hingga 4 foto dengan pengaturan jarak antarfoto dan kelengkungan sudut.
- **Koreksi Warna & Transformasi**: Pengaturan pencahayaan, kontras, saturasi, suhu warna, vinyet, rotasi, dan pemotongan rasio aspek.

---

## 🔒 Privasi & Keamanan

- **100% Offline**: Aplikasi tidak memerlukan dan tidak meminta izin internet (`android.permission.INTERNET` tidak disertakan di manifest).
- **Pemrosesan Lokal**: Seluruh pemrosesan foto dan model AI berjalan langsung di ponsel pengguna.
- **Tanpa Telemetri**: Tidak ada pelacakan, analitik, atau pengumpulan data pengguna.

---

## 🧠 Model Neural yang Digunakan (`app/src/main/assets/models/`)

Aplikasi menggunakan model neural open-source yang dioptimasi untuk NCNN:
- `scrfd_500m_kps-opt2`: Deteksi wajah dan titik landmark anatomis.
- `realesr-general-x4v3` & `realesr-general-wdn-x4v3`: Perbesaran resolusi 4X dan pengurangan bintik/noise.
- `modnet_matting`: Pemisahan helai rambut dan tepian subjek.
- `dpt256`: Estimasi kedalaman monokular untuk efek bokeh dan pencahayaan.
- `face_paint_512_v2`: Transfer gaya ilustrasi anime.

---

## 🛠️ Kompilasi & Build Lokal

```bash
# 1. Siapkan NCNN Android SDK:
./setup_ncnn.sh        # Linux / macOS
.\setup_ncnn.ps1       # Windows PowerShell

# 2. Jalankan Pengujian Unit:
./gradlew testDebugUnitTest

# 3. Kompilasi APK:
./gradlew assembleRelease assembleDebug
```
