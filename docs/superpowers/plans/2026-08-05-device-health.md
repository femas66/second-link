# Device Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan kartu ringkas dan halaman detail Device Health pada aplikasi Android SecondLink dengan refresh manual, refresh otomatis 30 detik, penilaian kesehatan yang teruji, dan tindakan cepat menuju pengaturan Android.

**Architecture:** Pisahkan model dan penilai kesehatan sebagai Kotlin murni, lalu gunakan adapter Android khusus untuk membaca baterai, penyimpanan, koneksi, uptime, layanan, izin, kontrol media, dan penghemat baterai. `BotViewModel` memiliki snapshot serta siklus refresh; Compose hanya merender state dan membuka intent pengaturan yang aman.

**Tech Stack:** Kotlin 2.2.10, Android SDK 26–37, Jetpack Compose Material 3, StateFlow, coroutines, JUnit 4.

## Global Constraints

- Fitur hanya ditambahkan pada aplikasi Android; command dan menu Telegram tidak diubah.
- Tidak menambah izin, dependency navigasi, server, telemetri, atau penyimpanan riwayat.
- Refresh otomatis berjalan setiap 30 detik hanya ketika halaman detail terlihat.
- Nama Wi-Fi dan alamat IP tidak dikumpulkan untuk UI Device Health.
- Semua tindakan cepat hanya membuka halaman pengaturan Android.
- Data gagal dibaca ditampilkan sebagai **Tidak tersedia**, bukan nol atau sehat.
- Status keseluruhan mengikuti masalah terburuk yang diketahui.
- Folder proyek saat ini bukan repositori Git; langkah commit dicatat sebagai titik serah tetapi dilewati sampai repositori diinisialisasi.

---

## Struktur File

- Create: `app/src/main/java/id/my/femasaf/secondlink/DeviceHealth.kt` — model domain dan penilai kesehatan murni.
- Create: `app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt` — pembacaan API Android dan normalisasi menjadi input domain.
- Create: `app/src/main/java/id/my/femasaf/secondlink/DeviceHealthUi.kt` — kartu dashboard, halaman detail, status visual, dan dispatcher tindakan pengaturan.
- Create: `app/src/test/java/id/my/femasaf/secondlink/DeviceHealthEvaluatorTest.kt` — pengujian seluruh batas penilaian.
- Create: `app/src/test/java/id/my/femasaf/secondlink/DeviceHealthSnapshotTest.kt` — pengujian urutan, masalah utama, dan data tidak tersedia.
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotViewModel.kt` — state, refresh manual, dan monitoring 30 detik.
- Modify: `app/src/main/java/id/my/femasaf/secondlink/MainActivity.kt` — navigasi lokal dashboard/detail dan pemasangan kartu.

---

### Task 1: Model Domain dan Penilai Kesehatan

**Files:**
- Create: `app/src/main/java/id/my/femasaf/secondlink/DeviceHealth.kt`
- Create: `app/src/test/java/id/my/femasaf/secondlink/DeviceHealthEvaluatorTest.kt`
- Create: `app/src/test/java/id/my/femasaf/secondlink/DeviceHealthSnapshotTest.kt`

**Interfaces:**
- Produces: `HealthLevel`, `HealthSection`, `DeviceHealthAction`, `BatteryCondition`, `DeviceHealthInput`, `DeviceHealthItem`, `DeviceHealthSnapshot`, dan `DeviceHealthEvaluator.evaluate(input, checkedAtMillis)`.
- Consumes: Tidak ada komponen Android atau Compose.

- [ ] **Step 1: Tulis pengujian gagal untuk batas suhu dan baterai**

Tambahkan test JUnit yang membangun input sehat, lalu memverifikasi:

```kotlin
assertEquals(HealthLevel.GOOD, evaluate(temperatureC = 39.9f).overallLevel)
assertEquals(HealthLevel.WARNING, evaluate(temperatureC = 40f).overallLevel)
assertEquals(HealthLevel.WARNING, evaluate(temperatureC = 44.9f).overallLevel)
assertEquals(HealthLevel.CRITICAL, evaluate(temperatureC = 45f).overallLevel)
assertEquals(HealthLevel.CRITICAL, evaluate(batteryPercent = 4, charging = false).overallLevel)
assertEquals(HealthLevel.WARNING, evaluate(batteryPercent = 5, charging = false).overallLevel)
assertEquals(HealthLevel.WARNING, evaluate(batteryPercent = 14, charging = false).overallLevel)
assertEquals(HealthLevel.GOOD, evaluate(batteryPercent = 15, charging = false).overallLevel)
assertEquals(HealthLevel.GOOD, evaluate(batteryPercent = 4, charging = true).overallLevel)
```

- [ ] **Step 2: Jalankan test dan pastikan gagal karena model belum tersedia**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealthEvaluatorTest' --no-daemon`

Expected: FAIL karena simbol `DeviceHealthEvaluator` dan model terkait belum didefinisikan.

- [ ] **Step 3: Implementasikan model domain minimum**

Gunakan bentuk API berikut:

```kotlin
enum class HealthLevel(val priority: Int) {
    UNAVAILABLE(0), GOOD(1), WARNING(2), CRITICAL(3)
}

enum class HealthSection { BATTERY, STORAGE, CONNECTION, SYSTEM, ACCESS }

enum class DeviceHealthAction {
    BATTERY_SETTINGS, STORAGE_SETTINGS, NETWORK_SETTINGS,
    APP_PERMISSIONS, MEDIA_ACCESS
}

enum class BatteryCondition { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN }

data class DeviceHealthInput(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val temperatureC: Float?,
    val batteryCondition: BatteryCondition,
    val storageAvailableBytes: Long?,
    val storageTotalBytes: Long?,
    val networkType: String?,
    val internetValidated: Boolean?,
    val uptimeMillis: Long?,
    val botStatus: String?,
    val cameraGranted: Boolean?,
    val contactsGranted: Boolean?,
    val notificationsGranted: Boolean?,
    val mediaAccessGranted: Boolean?,
    val batteryOptimizationIgnored: Boolean?
)

data class DeviceHealthItem(
    val id: String,
    val section: HealthSection,
    val title: String,
    val value: String,
    val level: HealthLevel,
    val detail: String,
    val action: DeviceHealthAction? = null
)

data class DeviceHealthSnapshot(
    val checkedAtMillis: Long,
    val overallLevel: HealthLevel,
    val items: List<DeviceHealthItem>
) {
    val primaryIssue: DeviceHealthItem?
        get() = items.firstOrNull { it.level == overallLevel && it.level.priority >= HealthLevel.WARNING.priority }
}

object DeviceHealthEvaluator {
    fun evaluate(input: DeviceHealthInput, checkedAtMillis: Long): DeviceHealthSnapshot
}
```

Implementasikan batas secara literal dari spesifikasi: suhu `>= 45` kritis dan `>= 40` peringatan; baterai tidak mengisi `< 5` kritis dan `< 15` peringatan.

- [ ] **Step 4: Jalankan test suhu dan baterai sampai hijau**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealthEvaluatorTest' --no-daemon`

Expected: PASS untuk seluruh kasus suhu dan baterai.

- [ ] **Step 5: Tambahkan pengujian gagal untuk penyimpanan, internet, layanan, akses, dan kondisi baterai Android**

Kasus wajib:

```kotlin
assertEquals(CRITICAL, evaluateStorage(freePercent = 4.99, freeBytes = 4L * GIB))
assertEquals(CRITICAL, evaluateStorage(freePercent = 20.0, freeBytes = GIB - 1))
assertEquals(WARNING, evaluateStorage(freePercent = 5.0, freeBytes = 2L * GIB))
assertEquals(GOOD, evaluateStorage(freePercent = 10.0, freeBytes = 2L * GIB))
assertEquals(CRITICAL, evaluate(internetValidated = false).overallLevel)
assertEquals(CRITICAL, evaluate(botStatus = "Error").overallLevel)
assertEquals(WARNING, evaluate(botStatus = "Stopped").overallLevel)
assertEquals(WARNING, evaluate(cameraGranted = false).overallLevel)
assertEquals(CRITICAL, evaluate(batteryCondition = BatteryCondition.DEAD).overallLevel)
assertEquals(WARNING, evaluate(batteryCondition = BatteryCondition.COLD).overallLevel)
```

- [ ] **Step 6: Jalankan test tambahan dan pastikan gagal pada aturan yang belum dibuat**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealthEvaluatorTest' --no-daemon`

Expected: FAIL pada assertion penyimpanan, koneksi, layanan, akses, atau kondisi baterai.

- [ ] **Step 7: Lengkapi penilai dan format nilai**

Bangun item dalam urutan stabil: level baterai, suhu, kondisi baterai, penyimpanan, internet, jenis jaringan, layanan, uptime, kamera, kontak, notifikasi, kontrol media, penghemat baterai. Gunakan `Locale.getDefault()` untuk angka tampilan dan format uptime `"%dj %02dm"` tanpa menyimpan data sensitif.

Pemetaan layanan:

```kotlin
private fun serviceLevel(status: String?): HealthLevel = when {
    status == null -> HealthLevel.UNAVAILABLE
    status.equals("Connected", ignoreCase = true) -> HealthLevel.GOOD
    status.equals("Disabled", ignoreCase = true) ||
        status.equals("Stopped", ignoreCase = true) ||
        status.equals("Inactive", ignoreCase = true) ||
        status.equals("Configuration Required", ignoreCase = true) -> HealthLevel.WARNING
    status.contains("error", ignoreCase = true) ||
        status.contains("fail", ignoreCase = true) -> HealthLevel.CRITICAL
    else -> HealthLevel.UNAVAILABLE
}
```

- [ ] **Step 8: Tambahkan test snapshot dan pastikan urutan serta unavailable benar**

Verifikasi bahwa daftar item selalu memiliki urutan yang sama, `primaryIssue` menunjuk masalah kritis pertama, nilai null menghasilkan `UNAVAILABLE`/`Tidak tersedia`, dan unavailable tidak mengalahkan masalah kritis lain.

- [ ] **Step 9: Jalankan seluruh test domain**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealth*Test' --no-daemon`

Expected: PASS, 0 failure.

- [ ] **Step 10: Titik serah Task 1**

Jika Git sudah tersedia: `git add app/src/main/java/id/my/femasaf/secondlink/DeviceHealth.kt app/src/test/java/id/my/femasaf/secondlink/DeviceHealthEvaluatorTest.kt app/src/test/java/id/my/femasaf/secondlink/DeviceHealthSnapshotTest.kt && git commit -m "feat: add device health evaluation"`. Pada folder saat ini, catat hasil test dan lanjut tanpa commit.

---

### Task 2: Sumber Data Android

**Files:**
- Create: `app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt`
- Create: `app/src/test/java/id/my/femasaf/secondlink/DeviceHealthInputFactoryTest.kt`

**Interfaces:**
- Consumes: `DeviceHealthInput` dan `BatteryCondition` dari Task 1.
- Produces: `DeviceHealthPlatformSnapshot`, `DeviceHealthInputFactory.create(platform, botStatus)`, dan `AndroidDeviceHealthSource.read(botStatus)`.

- [ ] **Step 1: Tulis test gagal untuk normalisasi snapshot platform**

Buat model antara tanpa Android dan verifikasi nilai mentah dipertahankan:

```kotlin
val input = DeviceHealthInputFactory.create(
    platform = DeviceHealthPlatformSnapshot(
        batteryPercent = null,
        charging = null,
        temperatureC = null,
        batteryCondition = BatteryCondition.UNKNOWN,
        storageAvailableBytes = 512L,
        storageTotalBytes = 0L,
        networkType = null,
        internetValidated = null,
        uptimeMillis = 90_000L,
        cameraGranted = true,
        contactsGranted = false,
        notificationsGranted = true,
        mediaAccessGranted = false,
        batteryOptimizationIgnored = false
    ),
    botStatus = "Connected"
)
assertNull(input.batteryPercent)
assertNull(input.storageTotalBytes)
assertEquals("Connected", input.botStatus)
assertFalse(input.mediaAccessGranted!!)
```

- [ ] **Step 2: Jalankan test dan pastikan gagal karena factory belum ada**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealthInputFactoryTest' --no-daemon`

Expected: FAIL karena `DeviceHealthInputFactory` belum tersedia.

- [ ] **Step 3: Implementasikan snapshot platform dan factory minimum**

`DeviceHealthInputFactory.create` harus mengubah kapasitas total `<= 0`, persentase baterai di luar `0..100`, suhu non-finite, dan uptime negatif menjadi null. Selain validasi tersebut, factory menyalin nilai platform tanpa mengubah status.

- [ ] **Step 4: Jalankan test factory hingga hijau**

Run: `./gradlew testDebugUnitTest --tests 'id.my.femasaf.secondlink.DeviceHealthInputFactoryTest' --no-daemon`

Expected: PASS.

- [ ] **Step 5: Implementasikan adapter Android**

Tambahkan:

```kotlin
class AndroidDeviceHealthSource(private val context: Context) {
    suspend fun read(botStatus: String?): DeviceHealthInput = withContext(Dispatchers.Default) {
        DeviceHealthInputFactory.create(readPlatformSnapshot(), botStatus)
    }
}
```

Gunakan API berikut dengan `runCatching` per kelompok data:

- `registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))` untuk level, scale, charging, temperatur, dan health.
- `StatFs(Environment.getDataDirectory().path)` untuk total/available bytes.
- `ConnectivityManager.activeNetwork` dan `NetworkCapabilities` untuk Wi-Fi/Seluler/Ethernet serta `NET_CAPABILITY_VALIDATED`.
- `SystemClock.elapsedRealtime()` untuk uptime.
- `ContextCompat.checkSelfPermission` untuk kamera, kontak, dan notifikasi Android 13+; notifikasi dianggap diberikan pada versi sebelumnya.
- `NotificationManagerCompat.getEnabledListenerPackages` untuk kontrol media.
- `PowerManager.isIgnoringBatteryOptimizations` untuk penghemat baterai.

Jangan membaca SSID atau alamat IP. Setiap kelompok yang melempar exception mengembalikan null hanya untuk kelompok tersebut.

- [ ] **Step 6: Kompilasi adapter Android**

Run: `./gradlew compileDebugKotlin --no-daemon`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Titik serah Task 2**

Jika Git tersedia: commit dengan pesan `feat: collect Android device health`. Pada folder saat ini, simpan hasil test dan kompilasi sebagai checkpoint.

---

### Task 3: State dan Siklus Refresh ViewModel

**Files:**
- Modify: `app/src/main/java/id/my/femasaf/secondlink/BotViewModel.kt`

**Interfaces:**
- Consumes: `AndroidDeviceHealthSource.read(botStatus)` dan `DeviceHealthEvaluator.evaluate(input, checkedAtMillis)`.
- Produces: `DashboardUiState.deviceHealth`, `isDeviceHealthRefreshing`, `deviceHealthError`, `refreshDeviceHealth()`, `startDeviceHealthMonitoring()`, dan `stopDeviceHealthMonitoring()`.

- [ ] **Step 1: Tambahkan state Device Health ke `DashboardUiState`**

```kotlin
val deviceHealth: DeviceHealthSnapshot? = null,
val isDeviceHealthRefreshing: Boolean = false,
val deviceHealthError: String? = null
```

- [ ] **Step 2: Tambahkan source, mutex, dan job monitoring**

```kotlin
private val deviceHealthSource = AndroidDeviceHealthSource(application)
private val deviceHealthMutex = Mutex()
private var deviceHealthMonitoringJob: Job? = null
```

Panggil `refreshDeviceHealth()` sekali pada `init` agar kartu dashboard memperoleh snapshot tanpa menunggu halaman detail dibuka.

- [ ] **Step 3: Implementasikan refresh tunggal yang mempertahankan snapshot lama saat gagal**

`refreshDeviceHealthNow()` harus:

1. Mengambil mutex agar refresh manual dan otomatis tidak tumpang tindih.
2. Mengatur `isDeviceHealthRefreshing = true` dan menghapus error lama.
3. Membaca source dengan `uiState.value.stats.status`.
4. Menilai snapshot menggunakan `System.currentTimeMillis()`.
5. Menyimpan snapshot baru dan menghentikan indikator loading.
6. Saat gagal, mempertahankan `deviceHealth`, mengatur pesan `"Pemeriksaan gagal. Coba lagi."`, dan menghentikan loading.

- [ ] **Step 4: Implementasikan monitoring idempoten 30 detik**

```kotlin
fun startDeviceHealthMonitoring() {
    if (deviceHealthMonitoringJob?.isActive == true) return
    deviceHealthMonitoringJob = viewModelScope.launch {
        refreshDeviceHealthNow()
        while (isActive) {
            delay(30_000L)
            refreshDeviceHealthNow()
        }
    }
}

fun stopDeviceHealthMonitoring() {
    deviceHealthMonitoringJob?.cancel()
    deviceHealthMonitoringJob = null
}
```

Batalkan job pada `onCleared()`.

- [ ] **Step 5: Kompilasi dan jalankan seluruh unit test**

Run: `./gradlew testDebugUnitTest compileDebugKotlin --no-daemon`

Expected: BUILD SUCCESSFUL, 0 failure.

- [ ] **Step 6: Titik serah Task 3**

Jika Git tersedia: commit dengan pesan `feat: manage device health refresh`. Pada folder saat ini, gunakan hasil test sebagai checkpoint.

---

### Task 4: Kartu Dashboard, Halaman Detail, dan Tindakan Cepat

**Files:**
- Create: `app/src/main/java/id/my/femasaf/secondlink/DeviceHealthUi.kt`
- Modify: `app/src/main/java/id/my/femasaf/secondlink/MainActivity.kt`

**Interfaces:**
- Consumes: `DeviceHealthSnapshot`, `DashboardUiState`, serta metode refresh/monitoring ViewModel dari Task 3.
- Produces: `DeviceHealthSummaryCard`, `DeviceHealthScreen`, dan `openDeviceHealthAction(context, action)`.

- [ ] **Step 1: Buat kartu ringkas yang menangani state awal, sehat, peringatan, dan kritis**

Signature:

```kotlin
@Composable
fun DeviceHealthSummaryCard(
    snapshot: DeviceHealthSnapshot?,
    isRefreshing: Boolean,
    error: String?,
    onOpen: () -> Unit
)
```

Gunakan kartu putih dengan border 1 dp, radius 12 dp, teks status dan ikon. Saat snapshot null, tampilkan **Memeriksa perangkat…**; saat error dan belum ada snapshot, tampilkan **Pemeriksaan belum tersedia**. Masalah utama berasal dari `snapshot.primaryIssue`.

- [ ] **Step 2: Buat halaman detail terkelompok dan dapat digulir**

Signature:

```kotlin
@Composable
fun DeviceHealthScreen(
    snapshot: DeviceHealthSnapshot?,
    isRefreshing: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DeviceHealthAction) -> Unit
)
```

Top bar berjudul **Device Health**, tombol kembali, tombol **Periksa Sekarang**, waktu pemeriksaan terakhir, lalu bagian Baterai, Penyimpanan, Koneksi, Sistem, dan Akses Penting. Setiap baris menampilkan judul, nilai, penjelasan, teks tingkat, dan tombol tindakan opsional. Warna selalu disertai teks/ikon.

- [ ] **Step 3: Implementasikan dispatcher tindakan dengan fallback aman**

Pemetaan intent:

```kotlin
BATTERY_SETTINGS -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
STORAGE_SETTINGS -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
NETWORK_SETTINGS -> Settings.ACTION_WIRELESS_SETTINGS
APP_PERMISSIONS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS + package Uri
MEDIA_ACCESS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
```

Gunakan `runCatching { context.startActivity(primaryIntent) }`. Jika gagal, coba `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` untuk package SecondLink. Jika fallback juga gagal, jangan melempar exception ke Compose.

- [ ] **Step 4: Pasang navigasi lokal pada dashboard**

Di `DashboardScreen`, simpan `var showDeviceHealth by rememberSaveable { mutableStateOf(false) }`. Ketika false, render dashboard dan tempatkan kartu Device Health setelah `StatusCard`. Ketika true, render `DeviceHealthScreen` dan gunakan `BackHandler` untuk kembali.

Gunakan `DisposableEffect(showDeviceHealth)` untuk memanggil `viewModel.startDeviceHealthMonitoring()` saat detail tampil dan `viewModel.stopDeviceHealthMonitoring()` pada `onDispose`. Tombol manual memanggil `viewModel.refreshDeviceHealth()`.

- [ ] **Step 5: Kompilasi UI**

Run: `./gradlew compileDebugKotlin --no-daemon`

Expected: BUILD SUCCESSFUL tanpa unresolved symbol Compose.

- [ ] **Step 6: Titik serah Task 4**

Jika Git tersedia: commit dengan pesan `feat: add device health dashboard`. Pada folder saat ini, gunakan hasil kompilasi sebagai checkpoint.

---

### Task 5: Verifikasi Akhir

**Files:**
- Verify: seluruh file pada Task 1–4.

**Interfaces:**
- Consumes: implementasi lengkap.
- Produces: bukti unit test, build debug, dan audit persyaratan.

- [ ] **Step 1: Jalankan unit test lengkap**

Run: `./gradlew testDebugUnitTest --no-daemon`

Expected: BUILD SUCCESSFUL, 0 failed tests.

- [ ] **Step 2: Bangun APK debug**

Run: `./gradlew assembleDebug --no-daemon`

Expected: BUILD SUCCESSFUL dan APK tersedia di `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Audit larangan dan kebocoran data**

Run: `rg -n "SSID|ssid|ipAddress|hostAddress|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION" app/src/main/java/id/my/femasaf/secondlink/DeviceHealth*.kt app/src/main/java/id/my/femasaf/secondlink/AndroidDeviceHealthSource.kt app/src/main/AndroidManifest.xml`

Expected: tidak ada pembacaan SSID/IP, dan tidak ada izin lokasi baru. Nama konstanta atau penjelasan test yang tidak membaca data harus diperiksa manual sebelum diterima.

- [ ] **Step 4: Audit kriteria spesifikasi**

Pastikan kartu dashboard, lima bagian detail, tiga tingkat status, nilai unavailable, refresh manual, refresh 30 detik, penghentian lifecycle, seluruh tindakan cepat, dan tidak adanya dependency/izin baru dapat ditunjuk ke implementasinya.

- [ ] **Step 5: Pemeriksaan manual pada perangkat atau emulator**

Buka dashboard, masuk ke Device Health, tekan **Periksa Sekarang**, tunggu lebih dari 30 detik, kembali ke dashboard, dan buka setiap tindakan cepat. Uji satu izin yang dimatikan dan satu kondisi tanpa internet untuk memastikan status serta petunjuk berubah setelah refresh.

- [ ] **Step 6: Laporkan batas verifikasi secara jujur**

Jika tidak ada perangkat/emulator aktif, laporkan bahwa test JVM dan build berhasil tetapi pemeriksaan intent OEM serta interval UI aktual masih memerlukan uji perangkat. Jangan menyatakan pemeriksaan manual berhasil tanpa bukti.
