# Desain: Device Health SecondLink

Tanggal: 2026-08-05  
Status: Disetujui oleh pengguna untuk spesifikasi; implementasi belum dimulai

## Tujuan

Menambahkan pusat kesehatan perangkat pada aplikasi Android SecondLink agar pemilik dapat melihat kondisi HP kedua dengan cepat, memahami masalah yang perlu ditangani, dan membuka pengaturan Android yang relevan. Fitur berada di aplikasi Android, bukan pada menu Telegram.

## Ruang lingkup

Device Health menampilkan kondisi perangkat saat ini tanpa menyimpan riwayat jangka panjang:

- Baterai: persentase, status pengisian, suhu, dan status kesehatan dari Android.
- Penyimpanan internal: kapasitas total, ruang tersedia, dan persentase ruang kosong.
- Koneksi: jenis jaringan aktif dan validasi akses internet.
- Sistem: waktu aktif perangkat dan status layanan SecondLink.
- Akses penting: kamera, kontak, notifikasi, Notification Listener untuk kontrol media, dan pengecualian penghemat baterai.
- Tindakan cepat yang membuka halaman pengaturan Android terkait.

Nama Wi-Fi, alamat IP, lokasi, dan data pribadi lain tidak ditampilkan. Fitur tidak menambah izin Android, server, telemetri, atau penyimpanan data kesehatan.

## Pengalaman pengguna

### Kartu ringkas pada dashboard

Dashboard memiliki kartu **Device Health** yang dapat ditekan. Kartu menampilkan:

- Status keseluruhan: **Baik**, **Perlu Perhatian**, atau **Kritis**.
- Waktu pemeriksaan terakhir.
- Masalah paling penting, atau pesan bahwa perangkat dalam kondisi baik.
- Petunjuk untuk membuka halaman detail.

Status tidak hanya dibedakan dengan warna; teks dan ikon selalu menyertai warna agar tetap jelas bagi pengguna dengan gangguan persepsi warna.

### Halaman detail

Menekan kartu membuka halaman detail Device Health dengan bagian Baterai, Penyimpanan, Koneksi, Sistem, dan Akses Penting. Setiap bagian menampilkan nilai utama dan statusnya dengan bahasa Indonesia yang mudah dipahami.

Halaman menyediakan tombol **Periksa Sekarang**. Selama halaman terlihat dan aplikasi berada di depan, data diperbarui otomatis setiap 30 detik. Pembaruan berhenti ketika halaman tidak terlihat atau aplikasi masuk ke latar belakang.

Jika data tertentu tidak tersedia pada perangkat, nilai ditampilkan sebagai **Tidak tersedia**. Nilai yang tidak tersedia tidak boleh diganti dengan nol atau dianggap sehat.

### Tindakan cepat

Masalah yang dapat ditangani memiliki tombol kontekstual:

- Penghemat baterai belum dikecualikan: **Buka Pengaturan Baterai**.
- Penyimpanan menipis: **Kelola Penyimpanan**.
- Internet tidak tersedia: **Periksa Jaringan**.
- Kamera, kontak, atau notifikasi belum diberikan: **Kelola Izin**.
- Akses kontrol media belum aktif: **Kelola Akses Media**.

Semua tindakan hanya membuka halaman resmi Android. SecondLink tidak mengubah pengaturan sistem secara diam-diam dan tidak menyatakan masalah selesai sebelum kondisi diperiksa kembali.

## Aturan penilaian

Setiap pemeriksaan menghasilkan tingkat **Baik**, **Perlu Perhatian**, **Kritis**, atau **Tidak Tersedia**. Status keseluruhan mengikuti tingkat masalah terburuk yang diketahui. Data yang tidak tersedia ditampilkan kepada pengguna, tetapi tidak otomatis menjadikan seluruh perangkat kritis.

### Kritis

- Suhu baterai minimal 45°C.
- Jaringan aktif tidak memiliki akses internet tervalidasi.
- Layanan SecondLink berada dalam kondisi gagal atau error.
- Ruang penyimpanan kosong kurang dari 5% atau kurang dari 1 GB; batas yang tercapai lebih dahulu berlaku.
- Baterai di bawah 5% dan tidak sedang mengisi daya.

### Perlu Perhatian

- Suhu baterai 40°C sampai kurang dari 45°C.
- Baterai di bawah 15% dan tidak sedang mengisi daya.
- Ruang penyimpanan kosong kurang dari 10%.
- Layanan SecondLink berhenti atau dinonaktifkan.
- Salah satu akses penting belum aktif.

### Baik

Semua data yang tersedia berada di luar batas Kritis dan Perlu Perhatian. Status kesehatan baterai dari Android yang menunjukkan overheat, dead, over-voltage, cold, atau unspecified failure meningkatkan tingkat sesuai tingkat keparahan yang dipetakan oleh penilai.

## Arsitektur

### Model domain

Model Device Health merupakan snapshot immutable yang memuat waktu pemeriksaan dan hasil setiap bagian. Model tidak bergantung pada Compose atau komponen UI Android sehingga aturan penilaian dapat diuji sebagai logika Kotlin biasa.

Tiap item kesehatan memuat nilai tampilan, tingkat, penjelasan singkat, dan jenis tindakan opsional. UI menerjemahkan jenis tindakan menjadi intent pengaturan Android; model domain tidak menyimpan `Context` atau menjalankan intent.

### Pengumpul data

Satu pengumpul khusus membaca API Android untuk baterai, penyimpanan, koneksi, waktu aktif, status layanan, izin aplikasi, Notification Listener, dan pengecualian penghemat baterai. Pengumpul menangani kegagalan per bagian agar satu API yang gagal tidak menghilangkan seluruh snapshot.

Pembacaan dilakukan saat halaman dibuka, saat pengguna menekan **Periksa Sekarang**, dan setiap 30 detik selama halaman aktif. Tidak ada service pemantauan permanen baru.

### State dan UI

`BotViewModel` menjadi pemilik state Device Health untuk dashboard. ViewModel memulai dan menghentikan pembaruan sesuai lifecycle halaman, mencegah beberapa refresh berjalan bersamaan, dan mempertahankan snapshot terakhir ketika refresh berikutnya gagal sebagian.

UI dipisahkan menjadi kartu ringkas dan halaman detail agar `MainActivity` tidak menampung seluruh logika penilaian. Navigasi cukup memakai state Compose lokal karena hanya terdapat dashboard dan halaman detail, sehingga tidak diperlukan dependency navigasi baru.

### Konsistensi dengan diagnostik Telegram

Logika pembacaan yang sama dapat digunakan kembali oleh diagnostik Telegram sehingga nilai baterai, penyimpanan, jaringan, dan uptime tidak dihitung dengan aturan berbeda. Format pesan Telegram tetap menjadi tanggung jawab `BotService`; perubahan ini tidak menambah atau menghapus command Telegram.

## Error handling

- Kegagalan satu sumber data menghasilkan **Tidak tersedia** hanya pada bagian tersebut.
- Jika refresh total tidak dapat dimulai, snapshot terakhir tetap terlihat bersama pesan kegagalan dan tombol **Coba Lagi**.
- Intent pengaturan yang tidak tersedia pada produsen Android tertentu ditangani tanpa crash dan dialihkan ke pengaturan aplikasi umum jika memungkinkan.
- Nilai baterai dengan scale tidak valid, kapasitas penyimpanan nol, atau network capability kosong tidak digunakan dalam perhitungan persentase.
- Tidak ada detail exception, token Telegram, kode akses, nama Wi-Fi, atau alamat IP yang ditampilkan pada UI.

## Pengujian

### Unit test

- Suhu 39,9°C, 40°C, 44,9°C, dan 45°C menghasilkan tingkat yang tepat.
- Baterai 4%, 5%, 14%, dan 15% diuji dalam keadaan mengisi dan tidak mengisi.
- Penyimpanan menguji batas 5%, 10%, dan 1 GB.
- Internet tidak tervalidasi dan layanan error menghasilkan Kritis.
- Layanan berhenti dan akses penting yang hilang menghasilkan Perlu Perhatian.
- Status keseluruhan memilih masalah terburuk.
- Data tidak tersedia tidak dianggap sehat dan tidak menimpa masalah kritis lain.
- Urutan masalah utama stabil agar kartu dashboard tidak berubah-ubah pada snapshot yang sama.

### Build dan pemeriksaan manual

- Build debug dan seluruh unit test berhasil.
- Kartu ringkas membuka dan menutup halaman detail dengan benar.
- **Periksa Sekarang** memperbarui waktu dan nilai.
- Pembaruan 30 detik hanya berjalan ketika halaman terlihat.
- Seluruh tombol tindakan membuka pengaturan yang relevan atau fallback yang aman.
- Tampilan diuji pada layar kecil dan tetap dapat digulir tanpa elemen terpotong.

## Kriteria selesai

- Dashboard menampilkan kartu Device Health dengan status dan masalah utama.
- Halaman detail menampilkan lima bagian yang ditentukan beserta data yang tersedia.
- Penilaian mengikuti seluruh batas pada spesifikasi ini.
- Refresh manual dan otomatis 30 detik bekerja sesuai lifecycle.
- Tombol tindakan cepat aman dan tidak mengubah pengaturan tanpa tindakan pengguna.
- Tidak ada izin, dependency navigasi, telemetri, atau penyimpanan riwayat baru.
- Unit test dan build debug berhasil.
