# ArAr Shot v3.1.1

### Perbaikan & Pembaruan
- **Perbaikan Mesin DCT-Net (Semua 6 Gaya Animasi Berfungsi):** Memperbaiki pembacaan dimensi input tensor 3D `[720, 720, 3]` / `[288, 288, 3]` pada model TFLite DCT-Net (*Animasi 3D*, *Artstyle*, *Kartun 2D*, *Ilustrasi*, *Desain*, *Sketsa*) serta menambahkan *auto-fallback* GPU ke CPU XNNPACK jika driver GPU perangkat menolak eksekusi.
- **Pintasan Langsung Gaya Animasi Terpasang di Beranda:** Setiap model animasi yang sudah diunduh otomatis muncul sebagai kartu pintasan berikon khusus di bagian **Gaya Animasi Terpasang** di halaman depan, langsung memilih foto dan masuk ke gaya tersebut.
- Termasuk seluruh pembaruan v3.1.0: 7 mesin animasi non-anime unduh manual, Studio Kolase fleksibel 1–9 foto, dan beranda bersih berbasis pintasan alat.
