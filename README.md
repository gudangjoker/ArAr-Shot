# ArAr Shot

Editor foto Android lokal untuk dokumentasi pribadi dan unggahan media sosial.

## Fitur Utama
- **Beranda Pintasan Alat:** Halaman depan bersih tanpa menampilkan galeri foto; setiap alat memiliki ikon dan pemilih fotonya sendiri.
- **Cahaya, Warna & HSL Linear-Light (C++17):** Eksposur fisik ($2^{\text{EV}}$) dengan *highlight roll-off*, kontras, *highlights/shadows* sadar tepi, *white balance*, vibrance, HSL 8 pita warna, *clarity*, *sharpen*, *vignette*, *grain*, *halation*, serta penyesuaian otomatis.
- **12 Filter Terkurasi:** Alami, Film, Sejuk, Senja, Lembut, Moody, Cerah, Matte, B&W, Noir, Retro, dan Sinema dengan slider kekuatan.
- **7 Mesin Animasi Non-Anime (Unduh Manual):**
  - **DCT-Net (SIGGRAPH 2022, TFLite):** Animasi 3D, Artstyle, Kartun 2D, Ilustrasi, Desain, dan Sketsa (pemrosesan 2 tahap: adegan penuh + perataan wajah 5-titik).
  - **White-box Cartoonization (CVPR 2020, TFLite):** Kartun Adegan untuk pemandangan, hewan, dan objek.
  - Semua model diunduh manual oleh pengguna agar ukuran APK tetap ringkas dan berjalan offline setelah terpasang.
- **Hapus & Ganti Latar:** Google ML Kit Subject Segmentation (orang, hewan, produk, objek umum) dengan *guided filter* & MODNet untuk tepi rambut, dukungan PNG transparan, warna solid, blur latar, bayangan studio, stiker, dan latar kustom.
- **Tingkatkan & Anti-Noise:** Real-ESRGAN 4X + *denoise*, *face restore* dengan SCRFD, serta penajaman.
- **Blur Kedalaman & Arah Cahaya:** Bokeh dengan peta kedalaman dan pencahayaan 3D.
- **Studio Kolase Fleksibel (1–9 Foto):** Banyak template layout untuk 1–9 foto, geser & zoom langsung per kotak, tukar posisi, putar, rasio `1:1` / `4:5` / `9:16` / `3:4` / `16:9`, kustomisasi bingkai & latar, ekspor resolusi tinggi.
- **Alat Lengkap Lainnya:** Potong & luruskan interaktif, hapus objek dengan kuas, teks di depan/belakang subjek, dan pasfoto (2×3, 3×4, 4×6, lembar cetak 4R).

## Build
Build APK dan pengujian unit dijalankan otomatis melalui GitHub Actions (`.github/workflows/android-build.yml`).
