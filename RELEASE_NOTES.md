# ArAr Shot v3.0.0

## Perubahan Utama
- **Antarmuka Baru yang Bersih:** Layar utama langsung menampilkan akses cepat galeri dan tombol Pilih Foto tanpa kartu deskripsi berlebihan; layar editor menempatkan foto sebagai pusat perhatian dengan kontrol bawah yang ringkas dan nyaman satu tangan.
- **Engine Warna Linear-Light Baru:** Pencahayaan (EV) dengan *highlight roll-off*, Sorotan/Bayangan *edge-aware*, *white balance* kromatik yang menjaga warna hitam tetap pekat, HSL 8-Warna, Auto Enhance, serta 12 preset filter dengan pratinjau langsung.
- **Perbaikan Hapus Latar Belakang:** Menggunakan ML Kit Subject Segmentation (mendukung manusia, produk, hewan, dan objek umum) dengan penyempurnaan MODNet + Guided Filter — memperbaiki bug lama yang memotong subjek secara tidak akurat.
- **Perbaikan Mode Anime, Bokeh & Wajah:** Menjaga proporsi asli pada AnimeGANv2, konvolusi bokeh lebih cepat di ruang *linear light*, restorasi wajah bekerja untuk semua ukuran foto, serta pemuatan model NCNN secara *lazy* agar aplikasi lebih ringan dan hemat RAM.
- **Crop Interaktif & Ekspor Cerdas:** Kotak potong geser langsung di foto, pelurusan sudut, pilihan ukuran ekspor optimal untuk media sosial, dan fitur Bagikan langsung.
