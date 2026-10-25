# 📸 ArAr Shot v2.0.1 — Catatan Rilis

**Versi**: 2.0.1  
**Tanggal Rilis**: 25 Oktober 2026  
**Package Name**: `com.ararshot.app`  

Pembaruan v2.0.1 menghadirkan penyempurnaan stabilitas, pembersihan informasi aplikasi, dan optimasi menyeluruh:

---

### ✨ Pembaruan & Peningkatan v2.0.1

1. **Penyempurnaan Penghapus Latar Belakang (*Background Remover*)**:
   - Pemrosesan *letterbox padding* (512x512) menjaga proporsi foto tetap asli tanpa distorsi bentuk subjek.
   - Algoritma fusi adaptif melindungi pakaian dan badan agar tidak terhapus secara kasar.
   - Penajaman batas subjek menggunakan *smoothstep* kubik untuk potongan tepian yang rapi dan halus.

2. **Antarmuka Pengguna & Bahasa Indonesia Penuh**:
   - Tampilan tema Aperture dengan kontras tinggi yang nyaman di mata.
   - 100% menggunakan Bahasa Indonesia pada seluruh menu, label alat, kontrol slider, dan dialog.

3. **Penyederhanaan Informasi & Kode Bersih**:
   - Pembersihan seluruh istilah berlebihan (*over-claim*) dan penyajian informasi aplikasi secara faktual.
   - Refaktor penamaan internal fungsi restorasi wajah menjadi `restoreFacesNeural`.

---

### 🔒 Privasi Terjamin

- 100% Offline: Pemrosesan AI dan foto berjalan langsung di perangkat Anda tanpa memerlukan akses internet (`android.permission.INTERNET` tidak disertakan).
- Tanpa pelacakan data, analitik, atau telemetri.
