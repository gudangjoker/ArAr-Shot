# ArAr Shot v3.1.0

Pembaruan v3.1.0 berfokus pada tiga perbaikan utama: penggantian fitur gaya anime menjadi **7 mesin animasi non-anime** yang bisa diunduh manual sesuai kebutuhan, **Studio Kolase fleksibel hingga 9 foto**, dan **beranda bebas galeri** berbasis pintasan alat.

### Pembaruan Utama
- **7 Mesin Animasi Non-Anime (Unduh Manual Sesuai Kebutuhan):**
  - Menghapus model lama `face_paint_512_v2` (AnimeGANv2) dari APK.
  - Menggantinya dengan **DCT-Net (Alibaba DAMO Academy, SIGGRAPH 2022)** dalam 6 gaya non-anime (*Animasi 3D*, *Artstyle*, *Kartun 2D*, *Ilustrasi*, *Desain*, *Sketsa*) menggunakan pemrosesan 2 tahap (latar penuh + perataan wajah 5-titik dengan SCRFD) dan **White-box Cartoonization (CVPR 2020)** (*Kartun Adegan*) untuk pemandangan/objek.
  - Setiap model dapat diunduh atau dihapus secara manual lewat **Model Animasi** (di beranda maupun di tab Animasi), dengan indikator progres unduhan dan penyimpanan offline lokal.
- **Studio Kolase Fleksibel (1–9 Foto):**
  - Mendukung pemilihan langsung hingga **9 foto** dengan beragam pilihan template layout untuk setiap jumlah foto (grid simetris, bento, majalah, fokus utama).
  - Penempatan foto interaktif di tiap kotak: **geser (pan)** dan **cubit (pinch-zoom)** langsung di dalam kotak, **tukar posisi (swap)** antar foto, **putar 90°**, **ganti foto**, dan **reset posisi**.
  - Pilihan rasio kanvas (`1:1`, `4:5`, `9:16`, `3:4`, `16:9`), pengaturan jarak, margin, sudut, 7 warna latar, serta ekspor resolusi tinggi (1800 px) dan tombol bagikan langsung.
- **Beranda Bersih Berbasis Pintasan Alat:**
  - Menghapus tampilan grid "Foto Terbaru" dari halaman utama (tidak perlu izin baca galeri).
  - Area utama kini berisi grid **Alat Cepat** dengan ikon masing-masing — setiap alat membuka pemilih foto sendiri dan langsung masuk ke alat yang dipilih.
