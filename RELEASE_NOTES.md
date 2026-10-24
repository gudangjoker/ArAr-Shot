# 📸 ArAr Shot Pro v.2.0.0 — Release Notes

## Versi 2.0.0 (Remini-Level 6-Model Offline SOTA AI Suite)
**Tanggal Rilis**: 25 Oktober 2026  
**Package Name**: `com.ararshot.app`  
**Tipe Rilis**: Edisi Studio Flagship (100% Offline Bundled SOTA Neural Suite)  
**Status Tanda Tangan**: Signed (Valid RSA 2048-bit Keystore)

---

### 🧠 6 Model SOTA Open-Source NCNN Terintegrasi Langsung (~77.2 MB Offline)
1. **SCRFD-500M-KPS (`scrfd_500m_kps-opt2.param/.bin`)** — Deteksi Wajah & 5 Titik Landmark Anatomis (Mata Kiri/Kanan, Hidung, Sudut Bibir) untuk **Remini Face & Portrait Restoration** serta auto-center biometrik Pasfoto Resmi ICAO.
2. **Real-ESRGAN x4v3 Crisp (`realesr-general-x4v3.param/.bin`)** — Rekonstruksi 4x Super-Resolution tekstur tajam.
3. **Real-ESRGAN x4v3 WDN (`realesr-general-wdn-x4v3.param/.bin`)** — Rekonstruksi 4x Super-Resolution dengan *Denoise* terintegrasi untuk foto low-light/kompresi WhatsApp.
4. **MODNet Alpha Matting (`modnet_matting.param/.bin`)** — Pemisahan helai rambut halus (*sub-pixel hair alpha matting*) yang dipadukan dengan $O(N)$ **Fast Guided Image Filter** (He et al.) dan *Foreground Color Decontamination*.
5. **Depth Anything (`dpt256.param/.bin`)** — Estimasi kedalaman 3D monokular (*continuous 3D depth map*) untuk **DSLR Optical Disc Bokeh** dan **3D Studio Normal-Map Relighting**.
6. **AnimeGANv2 (`face_paint_512_v2.param/.bin`)** — Neural style transfer portrait & lanskap anime Jepang, dikombinasikan dengan **Anisotropic Kuwahara Filter** dan kontur tinta *Difference-of-Gaussians* (DoG).

---

### 🔬 Peningkatan Presisi Matematis & C++17 OpenMP Engine (`libarar_engine.so`)
- **Remini-Level Face & Portrait Restoration (`MODE_FACE_RESTORE`)**:
  - Mendeteksi wajah & 5 landmark anatomis via SCRFD, melakukan 4x neural super-resolution pada area wajah, lalu menerapkan *Frequency Separation* ($\text{Low-Frequency Bilateral Skin Base} + \text{High-Frequency Pore/Hair Detail}$) serta peningkatan kontras mikro selektif pada iris mata, bulu mata, dan bibir, sebelum digabungkan kembali menggunakan *elliptical feathered mask*.
- **Zero-Seam 2D Hann/Cosine Tile Blending (`blendTileWithWeightNative`)**:
  - Menggunakan akumulator bobot `weightMap` persisten lintas seluruh tile dengan *2D Raised-Cosine Window* pada area *overlap*, menghilangkan bug penggelapan tile dan sambungan garis (*seam lines*).
- **Exact $O(N)$ Euclidean Distance Transform (Felzenszwalb-Huttenlocher EDT)**:
  - Menghasilkan *sticker outline* anti-aliased sub-piksel yang mulus sempurna dalam waktu $O(N)$ linear serta *drop shadow* Gaussian terpisah.
- **CIE LAB Perceptual Semantic Colorization**:
  - Mewarnai foto hitam-putih pada ruang krominansi $(a^*, b^*)$ dengan *edge-guided chrominance propagation* dan prior wajah/langit/dedaunan tanpa mengubah ketajaman luminansi $L^*$ asli.
- **Eikonal Gradient + Exemplar Patch Texture Inpainting**:
  - Memperbaiki *Magic Eraser* dengan proyeksi gradien orde pertama Telea dan transfer tekstur *high-frequency* dari *patch* tetangga terdekat.

---

### 🎨 Peningkatan UI/UX & Privasi
- **Undo & Redo Multi-Langkah**: Riwayat pengeditan non-destruktif langsung di `TopAppBar`.
- **Before/After Slider Lintas Tab**: Bandingkan foto asli vs hasil AI kapan saja di berbagai fitur studio.
- **Auto-Correction EXIF Orientation & Ekspor PNG Transparan Lossless**:
  - Foto portrait kamera otomatis tegak sesuai metadata EXIF, dan hasil hapus background (`TRANSPARENT`) otomatis diekspor sebagai `.png` 32-bit RGBA transparan.
- **100% Privasi Murni**: Menghapus izin mikrofon/audio yang tidak diperlukan; aplikasi hanya meminta izin Kamera & Foto Galeri tanpa izin Internet sama sekali.
