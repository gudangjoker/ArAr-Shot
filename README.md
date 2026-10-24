# 📷 ArAr Shot Pro v2.0.0 — Remini-Level Offline AI Studio

[![Android Build](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml/badge.svg)](https://github.com/gudangjoker/ArAr-Shot/actions/workflows/android-build.yml)
![Android Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue)
![Android Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-green)
![Release](https://img.shields.io/badge/Release-v2.0.0-purple)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Telemetry-success)

Aplikasi studio fotografi AI Android mandiri bertenaga **6 Model SOTA NCNN Terintegrasi** dan **C++17 OpenMP Native Engine (`libarar_engine.so`)** untuk restorasi wajah setara **Remini**, **4x Super-Resolution Real-ESRGAN**, **MODNet Hair Alpha Matting**, **Depth Anything 3D Bokeh & Relighting**, **AnimeGANv2 Style Transfer**, **CIE LAB Colorization**, dan **Magic Eraser** secara instan langsung di perangkat tanpa koneksi internet.

---

## 🧠 6 Model SOTA NCNN Terintegrasi (`app/src/main/assets/models/`)

| # | Model NCNN | Ukuran | Fungsi Utama |
| :--- | :--- | :--- | :--- |
| 1 | `scrfd_500m_kps-opt2` | 1.27 MB | Deteksi Wajah & 5 Titik Landmark Anatomis (Mata, Hidung, Bibir) untuk **Remini Face Restore** & Auto-Center Pasfoto ICAO |
| 2 | `realesr-general-x4v3` | 4.86 MB | **4x Super-Resolution Crisp** (Tekstur tajam berpresisi tinggi) |
| 3 | `realesr-general-wdn-x4v3` | 4.86 MB | **4x Super-Resolution + Denoise** (Untuk foto low-light & kompresi berat) |
| 4 | `modnet_matting` | 12.98 MB | **MODNet Portrait Alpha Matting** + $O(N)$ Fast Guided Filter (Pemisahan helai rambut halus) |
| 5 | `dpt256` | 49.02 MB | **Depth Anything Monocular 3D Depth** untuk DSLR Optical Bokeh & 3D Studio Relighting |
| 6 | `face_paint_512_v2` | 4.32 MB | **AnimeGANv2** + Anisotropic Kuwahara Cel-Shading & DoG Ink Contours |

---

## 🌟 Fitur Utama Studio

- **👤 Remini Face & Portrait Restoration**: Deteksi wajah & landmark 5 titik (SCRFD), rekonstruksi 4x neural super-resolution pada area wajah, *Frequency Separation* ($\text{Low-Freq Skin Smooth} + \text{High-Freq Pore Detail}$), serta penajaman mikro selektif pada iris mata dan bibir.
- **🚀 4x Super-Resolution Seamless**: Pemrosesan per-tile dengan akumulator bobot `weightMap` persisten dan *2D Raised-Cosine Hann Window* untuk sambungan 100% mulus tanpa garis batas (*zero-seam*).
- **✂️ MODNet Hair Cutout, Exact EDT Sticker & Drop Shadow**: Pemisahan latar belakang presisi helai rambut dengan *Foreground Color Decontamination*, *Exact $O(N)$ Euclidean Distance Transform* (Felzenszwalb-Huttenlocher) untuk outline stiker anti-aliased, dan ekspor PNG transparan lossless otomatis.
- **📸 AI Depth Anything Bokeh & 3D Relighting**: Estimasi peta kedalaman 3D sejati untuk *Circle-of-Confusion* DSLR lens bokeh dan pencahayaan *Half-Lambert + Blinn-Phong + Fresnel Rim* berbasis vektor normal permukaan 3D.
- **✨ Magic Eraser (Eikonal FMM + Patch Texture)**: Penghapusan objek pengganggu dengan *Fast Marching Method* bergradien Eikonal dan *Exemplar Patch Texture Transfer*.
- **🛡️ 100% Offline & Privasi Murni**: Tanpa izin internet (`android.permission.INTERNET` tidak disertakan), tanpa izin mikrofon/audio, tanpa telemetri.

---

## 🛠️ Kompilasi Lokal

```bash
# 1. Unduh & siapkan NCNN Android SDK:
./setup_ncnn.sh        # Linux / macOS
.\setup_ncnn.ps1       # Windows PowerShell

# 2. Jalankan Unit Test:
./gradlew testDebugUnitTest

# 3. Kompilasi APK Signed (Release & Debug):
./gradlew assembleRelease assembleDebug
```
