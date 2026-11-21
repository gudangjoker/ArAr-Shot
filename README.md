# ArAr Shot

Aplikasi editor foto native Android (Kotlin, Jetpack Compose, C++17 & NCNN) yang dirancang bersih, cepat, dan nyaman untuk kebutuhan dokumentasi pribadi maupun upload media sosial.

## Fitur Utama

- **Sesuaikan (Linear-Light):** Otomatis (Auto Enhance), Pencahayaan (EV + *highlight roll-off*), Kontras, Sorotan & Bayangan (*edge-aware*), Putih, Hitam, Suhu & Rona (*chromatic white balance*), Vibrance, Saturasi, **HSL 8-Warna**, Kejernihan, Ketajaman, Vinyet, Grain Film, Fade, dan Halation.
- **Filter:** 12 preset warna terkurasi dengan pratinjau langsung pada foto dan slider intensitas.
- **Potong & Luruskan:** Kotak potong interaktif langsung di foto, pelurusan sudut (±45°), putar, balik, serta rasio cepat sosial media (`4:5 IG`, `1:1`, `9:16`, `16:9`, `3:2`).
- **Tingkatkan:** Anti-Noise, Perjelas Wajah, Tajamkan, dan Resolusi 4X (Real-ESRGAN NCNN).
- **Hapus & Ganti Latar:** Pemisahan subjek umum (manusia, produk, hewan) menggunakan Google ML Kit Subject Segmentation dipadukan dengan MODNet & Guided Filter untuk tepian halus, ekspor PNG transparan, warna polos, gradasi, blur, bayangan, hingga stiker.
- **Blur & Pencahayaan:** Efek blur latar berbasis kedalaman (`dpt256`) di ruang *linear light* serta penyesuaian arah cahaya.
- **Hapus Objek:** Kuas penanda untuk menghilangkan noda atau objek pengganggu.
- **Gaya Anime:** Transformasi potret ke gaya Anime Halus, Ilustrasi, atau Sketsa dengan *letterbox padding* menjaga proporsi wajah.
- **Teks, Kolase & Pasfoto:** Tambah teks (termasuk di belakang subjek), kolase 2–4 foto, dan pasfoto ukuran `2×3`, `3×4`, `4×6`, `2×2` lengkap dengan lembar cetak 4R.
- **Simpan & Bagikan:** Pilihan ukuran (`Asli`, `Optimal Sosmed 1440 px`, `Ringan 1080 px`), format JPG/PNG, serta tombol Bagikan langsung.

## Build Lokal

```bash
./setup_ncnn.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleRelease
```
