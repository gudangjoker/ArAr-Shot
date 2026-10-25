# 📸 ArAr Shot v2.0.0 — Catatan Rilis

**Versi**: 2.0.0  
**Tanggal Rilis**: 25 Oktober 2026  
**Package Name**: `com.ararshot.app`  

Pembaruan v2.0.0 menghadirkan peningkatan performa pemrosesan, perbaikan algoritma penghapus latar belakang, perombakan antarmuka pengguna (UI), dan lokalisasi penuh bahasa Indonesia.

---

### ✨ Pembaruan & Fitur

1. **Perbaikan Penghapus Latar Belakang (*Background Remover*)**:
   - Penerapan *letterbox padding* ($512 \times 512$) untuk menjaga rasio aspek foto non-persegi agar batas subjek tidak terdistorsi.
   - Logika fusi adaptif antara ML Kit dan MODNet untuk melindungi area pakaian dan tubuh agar tidak terhapus.
   - Penyesuaian filter terarah (*guided filter*) dan transisi *smoothstep* untuk tepian anti-aliased yang bersih dan rapi.

2. **Restorasi Wajah & Resolusi 4X**:
   - Deteksi titik wajah (SCRFD) dan peningkatan resolusi area wajah menggunakan model Real-ESRGAN NCNN.
   - Pemisahan frekuensi untuk merapikan tekstur kulit dan memperjelas detail mata dan bibir.

3. **Efek Kedalaman 3D & Pencahayaan**:
   - Peta kedalaman monokular (Depth Anything) untuk efek blur lensa DSLR dan arah pencahayaan studio.

4. **Pewarnaan Foto Hitam-Putih**:
   - Rekonstruksi warna pada ruang warna CIE LAB untuk wajah, dedaunan, dan langit.

5. **Gaya Anime & Kamera Lawas**:
   - Filter ilustrasi anime (AnimeGANv2) dan nuansa kamera saku 2000-an lengkap dengan cap tanggal LCD.

6. **Pasfoto Resmi & Lembar Cetak 4R**:
   - Pilihan ukuran pasfoto resmi (2x3, 3x4, 4x6, visa) dengan latar belakang standar dan susunan kisi cetak 4R.

7. **Studio Kolase Foto**:
   - Penggabungan 2 hingga 4 foto dengan pengaturan jarak antarfoto dan sudut melengkung.

8. **Antarmuka & Bahasa Indonesia**:
   - Desain antarmuka baru bertema Aperture dengan kontras yang nyaman.
   - Seluruh teks, label alat, slider, dialog, dan pesan status menggunakan Bahasa Indonesia.

---

### 🔒 Privasi

- 100% Offline: Berjalan langsung di perangkat tanpa koneksi internet.
- Tanpa izin internet, tanpa audio/mikrofon, dan tanpa telemetri pelacakan.
