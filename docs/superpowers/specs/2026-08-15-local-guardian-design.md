# Desain: Local Guardian SecondLink

Tanggal: 2026-08-15  
Status: Desain disetujui; menunggu tinjauan spesifikasi tertulis

## Tujuan

Menambahkan pemantauan kejadian lokal pada SecondLink agar pemilik segera mengetahui perubahan penting pada HP kedua dan aplikasi dapat melakukan pemulihan terbatas yang aman. Seluruh pengamatan berjalan pada perangkat tanpa server eksternal.

Local Guardian berfokus pada peringatan berbasis perubahan kondisi, bukan laporan berkala. Jika perangkat kehilangan internet, kejadian disimpan secara lokal dan diringkas ke Telegram setelah koneksi pulih. Tanpa watchdog eksternal, SecondLink tidak menjanjikan peringatan saat perangkat sedang offline atau mati total.

## Ruang lingkup

Local Guardian memantau:

- tingkat dan status pengisian baterai;
- suhu baterai/perangkat yang tersedia melalui Android;
- ruang penyimpanan internal;
- validasi koneksi internet;
- kesehatan polling Telegram;
- restart service dan reboot perangkat;
- izin kamera, kontak, notifikasi, serta akses Notification Listener untuk kontrol media.

Fitur mencakup mesin evaluasi kejadian, jurnal insiden lokal, antrean peringatan offline, pengiriman Telegram, auto-recovery terbatas, tampilan ringkas pada dashboard Android, dan menu Guardian di Telegram.

## Bukan ruang lingkup

- Server, layanan heartbeat, atau watchdog eksternal.
- Mengaktifkan jaringan, izin, atau pengaturan Android secara diam-diam.
- Menghapus berkas pengguna atau membersihkan penyimpanan di luar area milik SecondLink.
- Memulai ulang perangkat, melakukan root, memakai Accessibility Service, atau bertindak sebagai device owner.
- Ambang batas yang dapat dikustomisasi pada versi pertama.
- Pelaporan kesehatan terjadwal jika tidak ada perubahan kondisi.

## Prinsip perilaku

- Peringatan mewakili perubahan keadaan, bukan setiap hasil pemeriksaan.
- Satu jenis masalah hanya memiliki satu insiden aktif.
- Auto-recovery harus terbatas, tercatat, dapat berhenti, dan tidak mengubah pengaturan sistem.
- Dashboard Android dan Telegram memakai sumber data serta aturan penilaian yang sama.
- Data yang tidak tersedia tidak dianggap normal dan tidak menghapus masalah lain yang sudah diketahui.
- Fungsi keselamatan tetap tersedia ketika pekerjaan berat dibatasi.

## Arsitektur

Local Guardian berjalan sebagai modul di dalam foreground `BotService`; desain tidak menambah service permanen lain.

### Signal Collector

Menggabungkan dua jenis sumber:

- Broadcast dan callback Android untuk perubahan baterai, pengisian, konektivitas, dan kejadian sistem yang tersedia.
- Pemeriksaan ringan berkala untuk penyimpanan, akses penting, serta kondisi lain yang tidak mempunyai sinyal langsung yang andal.

Collector menggunakan pembaca Device Health yang sudah ada bila datanya sama. Kegagalan satu sumber hanya membuat nilai terkait tidak tersedia.

### Guardian Evaluator

Logika Kotlin murni yang menerima snapshot terbaru dan state insiden sebelumnya. Evaluator menghasilkan transisi `NORMAL`, `WARNING`, `CRITICAL`, atau `RECOVERED`, termasuk waktu debounce, alasan, dan tindakan yang diizinkan. Evaluator tidak mengirim pesan, mengakses database, atau menjalankan API Android.

### Incident Journal

Room menyimpan:

- jenis dan tingkat insiden;
- waktu mulai, eskalasi, pengakuan, pemulihan, dan pembaruan terakhir;
- nilai kesehatan minimum yang diperlukan untuk menjelaskan insiden;
- jumlah serta hasil percobaan pemulihan;
- status pengiriman peringatan.

Riwayat dibatasi pada 30 hari atau 500 kejadian, mana yang tercapai lebih dahulu. Token bot, kode akses, kontak, alamat IP, nama Wi-Fi, URL unduhan, dan detail exception tidak disimpan.

### Alert Dispatcher

Menerjemahkan transisi insiden menjadi pesan Telegram. Ketika pengiriman gagal atau internet tidak tervalidasi, pesan disimpan pada outbox lokal. Saat koneksi kembali, beberapa perubahan dari insiden yang sama dipadatkan menjadi satu ringkasan kondisi terakhir beserta durasi gangguan.

### Recovery Controller

Menjalankan tindakan pemulihan yang secara eksplisit diizinkan. Setiap tindakan memiliki batas percobaan, backoff, dan hasil audit. Kegagalan berulang berakhir pada status `USER_ACTION_REQUIRED`; controller tidak melakukan loop tanpa akhir.

### Guardian Coordinator

Menerima sinyal secara serial, meminta snapshot, menjalankan evaluator, menyimpan transisi, memanggil Recovery Controller, dan menyerahkan peringatan ke dispatcher. Serialisasi mencegah satu kejadian diproses dua kali akibat callback yang berdekatan.

## Alur data

1. Android atau pemeriksaan berkala menghasilkan sinyal.
2. Collector membuat snapshot dari data yang tersedia.
3. Evaluator membandingkan snapshot dengan state insiden tersimpan.
4. Jika ada transisi, Incident Journal memperbarui insiden secara atomik.
5. Recovery Controller menjalankan tindakan yang diizinkan.
6. Alert Dispatcher mengirim pesan atau memasukkannya ke outbox.
7. Ketika kondisi pulih, insiden ditutup dan durasi serta hasil pemulihan dikirim.

## Aturan kejadian

### Baterai

- `WARNING`: di bawah 15% dan tidak sedang mengisi daya.
- `CRITICAL`: di bawah 5% dan tidak sedang mengisi daya.
- `RECOVERED`: perangkat mulai mengisi daya atau baterai mencapai minimal 20%.

Status mengisi daya langsung dapat memulihkan insiden agar pengguna tidak menerima pengingat saat perangkat sudah ditangani.

### Suhu

- `WARNING`: minimal 40°C pada dua pemeriksaan berturut-turut.
- `CRITICAL`: minimal 45°C pada dua pemeriksaan berturut-turut, atau eskalasi dari warning saat batas kritis tercapai.
- `RECOVERED`: di bawah 38°C secara berkelanjutan selama lima menit.

### Penyimpanan

- `WARNING`: ruang kosong di bawah 10%.
- `CRITICAL`: ruang kosong di bawah 5% atau kurang dari 1 GB; batas yang lebih dahulu tercapai berlaku.
- `RECOVERED`: ruang kosong minimal 12% dan minimal 1,5 GB.

Kedua syarat pemulihan harus terpenuhi agar status tidak berulang di sekitar ambang.

### Internet

- Insiden dibuka jika internet tidak tervalidasi selama dua menit.
- Gangguan di bawah dua menit diabaikan.
- Insiden ditutup ketika Android kembali melaporkan internet tervalidasi.
- Karena Telegram tidak dapat dijangkau saat offline, ringkasan dikirim setelah pulih dan memuat durasi gangguan.

Insiden internet tidak memakai eskalasi kritis karena tingkat tersebut tidak meningkatkan kemampuan aplikasi untuk menghubungi pemilik.

### Polling Telegram

- `WARNING`: tiga kegagalan polling berturut-turut.
- `CRITICAL`: polling belum pulih selama 15 menit.
- `RECOVERED`: polling berhasil kembali.

Jika transport Telegram gagal, peringatan disimpan dan dikirim saat transport pulih.

### Service dan reboot

- Startup mencatat alasan yang dapat diketahui: boot perangkat, start pengguna, atau restart service.
- Setelah reboot, Guardian membuat sesi pemantauan baru dan mengirim informasi bahwa perangkat kembali aktif setelah Telegram dapat dijangkau.
- Jika Android mematikan proses, mekanisme restart service yang diizinkan platform digunakan. Pemeriksaan cadangan periodik boleh mendeteksi service tidak aktif, tetapi tidak menjanjikan restart real-time.

### Akses penting

- Insiden `WARNING` dibuka ketika izin kamera, kontak, notifikasi, atau akses media yang sebelumnya tersedia menjadi tidak tersedia.
- Insiden ditutup setelah akses tersedia kembali.
- Guardian tidak mencoba memberikan izin atau mengaktifkan akses secara otomatis.

## Debounce, deduplikasi, dan pengingat

- Transisi dikirim sekali ketika insiden dibuka, meningkat, diakui, atau pulih.
- Setiap jenis kejadian hanya memiliki satu insiden aktif.
- Kejadian singkat harus melewati debounce yang ditentukan sebelum membuka insiden.
- Eskalasi dari warning ke critical selalu menghasilkan peringatan baru.
- Insiden critical yang belum diakui dapat diingatkan setiap enam jam, maksimal tiga kali dalam satu hari kalender lokal.
- Tombol **Akui** menghentikan pengingat berkala untuk insiden tersebut, tetapi tidak menutup insiden atau menghentikan pemantauan.
- Pemulihan selalu menghasilkan pesan jika pesan pembukaan pernah dikirim. Jika seluruh insiden terjadi saat offline, outbox mengirim satu ringkasan selesai, bukan rangkaian pesan yang sudah kedaluwarsa.

## Auto-recovery

### Polling Telegram

Koneksi polling dibuat ulang setelah 5, 15, 30, dan 60 detik, lalu paling sering setiap lima menit. Setelah koneksi stabil, urutan retry kembali ke awal. Hanya satu upaya reconnect boleh berjalan pada satu waktu.

### Internet terputus

Pengiriman Telegram dihentikan dan pesan masuk outbox. Guardian menunggu callback konektivitas serta pemeriksaan validasi ringan; ia tidak mengaktifkan Wi-Fi atau data seluler.

### Suhu kritis

Kamera, unduhan, pemindaian jaringan, dan cron berat ditolak sementara dengan alasan yang jelas. `/status`, penghentian alarm, pemeriksaan Guardian, dan fungsi keselamatan tetap tersedia. Perintah interaktif yang ditolak tidak diantrekan untuk dijalankan kemudian. Cron yang dilewati dicatat sebagai `SKIPPED_SAFETY` agar tidak berjalan terlambat tanpa konteks.

### Baterai kritis

Pekerjaan latar nonpenting dan frekuensi pemeriksaan yang mahal dikurangi. Guardian, konektivitas, polling dengan backoff, `/status`, dan penghentian alarm tetap aktif.

### Penyimpanan kritis

Unduhan baru ditolak. Guardian boleh membersihkan cache, foto kamera sementara, dan file unduhan parsial milik SecondLink. Pembersihan memakai target eksplisit dalam direktori aplikasi dan tidak menyentuh media atau berkas pengguna.

### Akses dicabut

Guardian hanya mengirim petunjuk dan menampilkan tindakan untuk membuka pengaturan Android yang relevan. Tidak ada retry untuk memberikan izin.

## Pengalaman Telegram

Menu utama mendapat tombol **Guardian**. Halaman Guardian menampilkan:

- status `Aktif`, `Terbatas`, atau `Perlu tindakan`;
- jumlah insiden aktif;
- waktu pemeriksaan terakhir;
- jumlah pesan tertunda;
- tombol **Insiden Aktif**, **Riwayat**, **Periksa Sekarang**, dan **Uji Peringatan**.

Peringatan mempunyai tombol **Periksa Lagi**, **Lihat Status**, **Akui**, dan **Buka Menu Guardian**. Seluruh callback mengikuti otorisasi chat, owner binding, rate limit, nonce, dan sesi keamanan yang sudah digunakan SecondLink. Aksi baca dan pengakuan insiden tidak menjalankan kontrol perangkat; tindakan berat tetap mengikuti kebijakan command sensitif.

Contoh pesan:

```text
🔴 Suhu perangkat kritis: 46,2°C
Terdeteksi selama 3 menit. Aktivitas latar nonpenting dikurangi.
Baterai 48% • Tidak mengisi daya.
```

## Pengalaman Android

Dashboard mendapat kartu **Local Guardian** yang menampilkan status keseluruhan, insiden utama, pemeriksaan terakhir, dan jumlah pesan tertunda. Halaman detail menampilkan insiden aktif dan riwayat lokal.

Guardian aktif ketika Bot Service aktif dan auto-recovery aktif secara bawaan. Versi pertama tidak menyediakan editor ambang batas. UI menggunakan ikon dan teks bersama warna agar tingkat masalah tetap dapat dibedakan tanpa bergantung pada persepsi warna.

## Penyatuan jalur perintah

Command teks, tombol Telegram, dan cron harus memakai dispatcher aplikasi yang sama. Dispatcher bertanggung jawab atas otorisasi, aturan keselamatan Guardian, eksekusi handler, dan hasil audit. Perubahan ini diperlukan karena saat ini sebagian tombol menu tidak memiliki cakupan handler yang sama dengan command teks.

Handler tidak boleh memanggil parser Telegram lain secara rekursif. Setiap kemampuan memiliki operasi internal bertipe yang dapat dipanggil oleh adapter command teks, callback menu, dan cron dengan kebijakan yang sesuai sumbernya.

## Penanganan kegagalan

- Kegagalan satu sumber data menghasilkan nilai tidak tersedia hanya untuk sumber tersebut.
- Kegagalan Telegram mempertahankan pesan pada outbox.
- Transaksi Room menjaga perubahan insiden dan outbox tetap konsisten.
- State tersimpan yang tidak dapat dibaca tidak dianggap sehat; Guardian membuat state konservatif, mencatat error teredaksi, dan meminta pemeriksaan baru.
- Debounce dan cooldown dalam satu sesi memakai waktu monotonic Android agar perubahan jam dinding tidak menghasilkan durasi salah. Waktu dinding disimpan hanya untuk tampilan dan retensi.
- Reboot mengakhiri basis waktu monotonic lama dan membuat sesi baru; state insiden yang belum selesai ditandai terinterupsi lalu dievaluasi ulang dari snapshot baru.
- Exception hanya dicatat sebagai kategori teredaksi tanpa stack trace atau nilai rahasia pada database dan Telegram.

## Pengujian

### Unit test

- Batas baterai 4%, 5%, 14%, 15%, dan 20% dalam keadaan mengisi serta tidak mengisi.
- Suhu tepat sebelum, pada, dan sesudah 38°C, 40°C, dan 45°C, termasuk dua sampel serta pemulihan lima menit.
- Penyimpanan pada batas 5%, 10%, 12%, 1 GB, dan 1,5 GB.
- Seluruh transisi normal, warning, critical, acknowledged, recovered, dan interrupted.
- Debounce, deduplikasi, cooldown, eskalasi, batas tiga pengingat, dan pergantian hari lokal.
- Pemadatan outbox ketika satu insiden berubah beberapa kali saat offline.
- Backoff polling dan jaminan satu reconnect pada satu waktu.
- Pemblokiran operasi berat serta pengecualian fungsi keselamatan.
- Retensi 30 hari dan 500 kejadian.
- Redaksi data insiden dan audit.

### Integrasi

- Collector palsu menggerakkan Coordinator, Room, Recovery Controller, dan dispatcher tanpa perangkat fisik.
- Migrasi database mempertahankan konfigurasi, cron, reminder, dan audit log yang sudah ada.
- Command teks, callback Telegram, dan cron mencapai operasi internal yang sama.
- Pesan gagal tersimpan lalu terkirim setelah koneksi pulih.
- Restart service dan reboot tidak menggandakan insiden atau pengingat.

### Pemeriksaan manual

- Reboot perangkat dan start ulang service.
- Mode pesawat lebih dari dan kurang dari dua menit.
- Pencabutan serta pemulihan setiap akses penting.
- Simulasi baterai rendah, pengisian, suhu, dan penyimpanan bila perangkat atau emulator mendukung.
- Penolakan kamera, unduhan, scan, dan cron berat pada kondisi keselamatan.
- Pembersihan hanya menghapus file sementara milik aplikasi.
- Tampilan Telegram dan Android tetap konsisten pada layar kecil serta saat outbox berisi pesan.

## Urutan implementasi

1. Model domain, evaluator, dan unit test batas serta transisi.
2. Schema Room untuk insiden dan outbox beserta migrasi.
3. Collector dan Guardian Coordinator yang memakai Device Health source.
4. Alert Dispatcher, pemadatan outbox, dan Recovery Controller.
5. Penyatuan dispatcher command teks, menu, dan cron serta safety gate.
6. Menu Telegram Guardian dan interaksi insiden.
7. Kartu serta halaman Local Guardian pada dashboard Android.
8. Pengujian integrasi, build, dan pemeriksaan manual perangkat.

## Kriteria selesai

- Guardian mendeteksi dan menilai seluruh kejadian yang ditentukan dengan ambang yang tepat.
- Peringatan hanya muncul pada transisi yang valid dan mematuhi debounce serta batas pengingat.
- Gangguan offline menghasilkan satu ringkasan yang benar setelah koneksi pulih.
- Auto-recovery selalu terbatas, tercatat, dan berhenti pada status perlu tindakan pengguna.
- Operasi berat diblokir pada kondisi keselamatan tanpa memblokir `/status` dan penghentian alarm.
- Tidak ada aksi yang mengubah jaringan atau izin secara diam-diam.
- Jurnal tidak menyimpan data rahasia dan retensi berjalan sesuai batas.
- Tombol Telegram, command teks, dan cron konsisten melalui dispatcher bersama.
- Dashboard Android dan Telegram menampilkan state Guardian yang sama.
- Migrasi database, seluruh unit test, pengujian integrasi, dan build debug berhasil.
