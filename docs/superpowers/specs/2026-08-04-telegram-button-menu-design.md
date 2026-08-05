# Desain: Telegram Button Command Center

Tanggal: 2026-08-04  
Status: Disetujui oleh pengguna untuk spesifikasi; implementasi belum dimulai

## Tujuan

Menjadikan tombol Telegram sebagai cara utama mengoperasikan SecondLink, tanpa menghapus command teks yang sudah ada. Pengguna iPhone dapat mengakses fungsi Android kedua melalui menu yang mudah dipahami, sementara command teks tetap menjadi fallback ketika tombol tidak tersedia atau gagal.

## Ruang lingkup

Menu mencakup seluruh kemampuan yang sudah tersedia:

- Status dan diagnostik perangkat
- Kontrol media
- Kamera depan/belakang
- Kontak
- Scan Wi‑Fi
- Alarm pencarian
- Download file
- Reminder
- CronJob
- Pengaturan keamanan dan penutupan menu

Fitur baru yang tidak diperlukan untuk navigasi tombol—seperti lokasi atau notifikasi—tidak termasuk dalam implementasi ini.

## Pengalaman pengguna

### Menu utama

Bot mengirim satu pesan menu dengan inline keyboard:

```text
SecondLink Remote Control
Status: Connected
Media: Ready / Access required
Security: Locked / Unlocked (expires in …)
```

Tombol utama:

```text
[Status]       [Media]
[Kamera]       [Perangkat]
[Jadwal]       [File]
[Keamanan]     [Tutup Menu]
```

Bot mengedit pesan menu yang sama ketika pengguna berpindah submenu. Pesan callback sementara dijawab dengan `answerCallbackQuery` agar Telegram berhenti menampilkan spinner.

### Submenu

- **Status:** status perangkat, refresh, kembali.
- **Media:** status, daftar sesi, pilih pemutar, play/pause/toggle, previous/next, seek, antrean, volume, speed, custom actions, kembali.
- **Kamera:** depan, belakang, kembali.
- **Perangkat:** daftar kontak, scan Wi‑Fi, nyalakan alarm, matikan alarm, kembali.
- **Jadwal:** daftar cron, tambah cron, hapus cron, reminder baru, kembali.
- **File:** mulai download, status/alur download, batal, kembali.
- **Keamanan:** buka akses, kunci sekarang, status sesi, kembali.

### Alur input terpandu

Tombol yang membutuhkan teks atau angka membuka state percakapan sementara:

- Download: bot meminta URL HTTPS, lalu menampilkan konfirmasi nama file.
- Reminder: bot meminta durasi, kemudian pesan, lalu menampilkan konfirmasi.
- CronJob: bot meminta waktu, kemudian command yang diizinkan, lalu konfirmasi.
- Pilih media: bot menampilkan daftar sesi dan tombol nomor sesi.

Setiap alur mempunyai tombol **Batal**, batas waktu lima menit, batas panjang input, dan hanya dapat dilanjutkan oleh Chat ID/user yang memulainya.

## Keamanan unlock

Status awal selalu `Locked`. Status unlock disimpan hanya di memori service, bukan di database atau backup.

Alur:

1. Pengguna menekan **Buka Akses**.
2. Bot meminta kode akses.
3. Pengguna mengirim kode melalui pesan teks fallback.
4. Bot memverifikasi hash lokal, menghapus pesan kode melalui Telegram, dan hanya jika penghapusan berhasil membuat sesi unlock.
5. Sesi berisi Chat ID, Telegram user ID, waktu mulai, waktu kedaluwarsa, dan nonce acak.
6. Sesi berlaku lima menit, hanya untuk callback dari Chat ID/user yang sama.
7. Tombol **Kunci Sekarang** menghapus sesi segera.
8. Callback sensitif setelah kedaluwarsa ditolak dan diarahkan ke **Buka Akses**.

Status dan bantuan boleh digunakan tanpa unlock. Kamera, kontak, scan Wi‑Fi, file, alarm, jadwal yang mengubah perangkat, dan seluruh kontrol media memerlukan unlock.

Callback tidak mempercayai label tombol. Server memvalidasi token callback secara ketat terhadap action yang diizinkan, pengguna, chat, nonce, versi menu, dan expiry.

## Arsitektur

### Model Telegram

Tambahkan dukungan untuk:

- `callback_query` pada update
- `inline_keyboard` pada `sendMessage`/`editMessageText`
- `answerCallbackQuery`
- `editMessageText`
- `deleteMessage` yang sudah tersedia untuk menghapus kode unlock

ID callback memakai bentuk pendek dan berversi, misalnya `v1:media:play`. Tidak ada rahasia atau URL mentah di dalam callback data.

### Router callback

Satu router memproses callback dalam urutan:

1. Validasi callback dan usia pesan.
2. Validasi Chat ID dan Telegram user ID.
3. Validasi versi menu dan action whitelist.
4. Cek expiry state input atau sesi unlock.
5. Terapkan rate limit dan mutex command.
6. Panggil handler yang sama dengan command teks.
7. Edit pesan menu dengan hasil atau submenu baru.
8. Audit hanya nama action, bukan kode, URL, atau isi pesan pengguna.

Command teks tetap masuk melalui router teks yang ada, tetapi handler inti dibagi agar tombol dan teks menghasilkan perilaku yang sama.

### State

State sementara dikelola in-memory per Chat ID/user:

- Menu message ID
- Menu version
- Current screen
- Pending input type
- Pending input expiry
- Unlock expiry dan nonce
- Selected media package

State dihapus ketika service berhenti, setelah timeout, ketika pengguna menekan batal, atau ketika Chat ID berubah.

## Error handling

- Jika akses media belum diberikan, tombol Media menampilkan instruksi membuka Notification access.
- Jika callback kedaluwarsa, bot menampilkan tombol Refresh Menu.
- Jika aksi tidak didukung aplikasi pemutar, bot menyebutkan keterbatasan aksi tersebut dan tidak menganggapnya sukses.
- Jika Telegram API gagal, menu tetap mempertahankan layar terakhir dan menyediakan tombol coba lagi.
- Jika input tidak valid, bot meminta input ulang dengan contoh yang aman.
- Jika command teks digunakan, hasil tetap dikirim sebagai pesan biasa tanpa merusak menu utama.
- Tidak ada exception detail, token, kode akses, atau URL pribadi yang dikirim ke Telegram atau Logcat.

## Batasan yang diterima

- Telegram inline keyboard tidak dapat memasukkan URL, durasi, atau pesan arbitrer tanpa state input tambahan; karena itu fungsi tersebut memakai percakapan terpandu.
- Kontrol media mengikuti capability MediaSession aplikasi pemutar. Repeat/shuffle dan custom action hanya tersedia jika pemutar mempublikasikannya.
- Akses Notification Listener harus diaktifkan manual oleh pengguna Android.
- Unlock berbasis kode tetap bergantung pada keamanan akun Telegram selama pesan kode sedang dikirim; pesan kode dihapus segera setelah verifikasi berhasil.

## Pengujian

### Unit test

- Callback parser menolak action tidak dikenal, versi salah, data terlalu panjang, dan format yang mengandung rahasia.
- Validasi user/chat menolak callback dari akun atau chat berbeda.
- Unlock berhasil, gagal, kedaluwarsa, dikunci manual, dan tidak dapat dipakai lintas Chat ID.
- State input timeout, batal, dan input out-of-order.
- Setiap tombol memetakan ke handler command yang benar.
- Menu media menghasilkan callback yang benar untuk sesi, antrean, volume, seek, dan custom action.

### Instrumentasi/manual test

- Tap semua tombol dari Telegram iPhone.
- Uji menu saat bot offline lalu pulih.
- Uji callback lama setelah menu diedit.
- Uji callback dari akun Telegram lain.
- Uji kode salah, kode benar, expiry lima menit, dan lock manual.
- Uji Spotify/YouTube Music/pemutar lokal dengan capability media berbeda.
- Uji perangkat tanpa Notification access.
- Uji input URL, reminder, dan cron yang terlalu panjang atau tidak valid.

## Kriteria selesai

- Semua fungsi lama memiliki jalur tombol yang terlihat dan dapat kembali ke menu utama.
- Semua command teks lama tetap berfungsi.
- Tidak ada aksi sensitif yang dapat dijalankan melalui callback tanpa unlock valid.
- Callback dari user/chat lain selalu ditolak.
- Menu tidak menumpuk pesan baru pada penggunaan normal.
- Build debug, build release, unit test, lint, dan manifest verification berhasil.
