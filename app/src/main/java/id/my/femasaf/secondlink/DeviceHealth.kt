package id.my.femasaf.secondlink

import java.util.Locale
import kotlin.math.max

enum class HealthLevel(val priority: Int) {
    UNAVAILABLE(0),
    GOOD(1),
    WARNING(2),
    CRITICAL(3)
}

enum class HealthSection {
    BATTERY,
    STORAGE,
    CONNECTION,
    SYSTEM,
    ACCESS
}

enum class DeviceHealthAction {
    BATTERY_SETTINGS,
    STORAGE_SETTINGS,
    NETWORK_SETTINGS,
    APP_PERMISSIONS,
    MEDIA_ACCESS
}

enum class BatteryCondition {
    GOOD,
    OVERHEAT,
    DEAD,
    OVER_VOLTAGE,
    COLD,
    FAILURE,
    UNKNOWN
}

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
        get() = items.firstOrNull {
            it.level == overallLevel && it.level.priority >= HealthLevel.WARNING.priority
        }
}

data class DeviceHealthLoadState(
    val snapshot: DeviceHealthSnapshot? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

object DeviceHealthLoadReducer {
    fun started(current: DeviceHealthLoadState): DeviceHealthLoadState = current.copy(
        isRefreshing = true,
        error = null
    )

    fun succeeded(
        current: DeviceHealthLoadState,
        snapshot: DeviceHealthSnapshot
    ): DeviceHealthLoadState = current.copy(
        snapshot = snapshot,
        isRefreshing = false,
        error = null
    )

    fun failed(current: DeviceHealthLoadState): DeviceHealthLoadState = current.copy(
        isRefreshing = false,
        error = "Pemeriksaan gagal. Coba lagi."
    )
}

object DeviceHealthEvaluator {
    private const val GIB = 1_073_741_824L

    fun evaluate(input: DeviceHealthInput, checkedAtMillis: Long): DeviceHealthSnapshot {
        val items = listOf(
            batteryLevelItem(input.batteryPercent, input.charging),
            temperatureItem(input.temperatureC),
            batteryConditionItem(input.batteryCondition),
            storageItem(input.storageAvailableBytes, input.storageTotalBytes),
            internetItem(input.internetValidated),
            networkTypeItem(input.networkType),
            serviceItem(input.botStatus),
            uptimeItem(input.uptimeMillis),
            accessItem("camera", "Kamera", input.cameraGranted, DeviceHealthAction.APP_PERMISSIONS),
            accessItem("contacts", "Kontak", input.contactsGranted, DeviceHealthAction.APP_PERMISSIONS),
            accessItem("notifications", "Notifikasi", input.notificationsGranted, DeviceHealthAction.APP_PERMISSIONS),
            accessItem("media_access", "Kontrol media", input.mediaAccessGranted, DeviceHealthAction.MEDIA_ACCESS),
            batteryOptimizationItem(input.batteryOptimizationIgnored)
        )
        val knownLevels = items.map { it.level }.filter { it != HealthLevel.UNAVAILABLE }
        val overall = knownLevels.maxByOrNull { it.priority } ?: HealthLevel.UNAVAILABLE
        return DeviceHealthSnapshot(checkedAtMillis, overall, items)
    }

    private fun batteryLevelItem(percent: Int?, charging: Boolean?): DeviceHealthItem {
        val level = when {
            percent == null -> HealthLevel.UNAVAILABLE
            charging == false && percent < 5 -> HealthLevel.CRITICAL
            charging == false && percent < 15 -> HealthLevel.WARNING
            charging == null && percent < 15 -> HealthLevel.UNAVAILABLE
            else -> HealthLevel.GOOD
        }
        val value = percent?.let { "$it%" } ?: "Tidak tersedia"
        val chargingText = when (charging) {
            true -> "sedang mengisi daya"
            false -> "tidak mengisi daya"
            null -> "status pengisian tidak tersedia"
        }
        val detail = when (level) {
            HealthLevel.CRITICAL -> "Baterai hampir habis dan $chargingText."
            HealthLevel.WARNING -> "Baterai rendah dan $chargingText."
            HealthLevel.GOOD -> "Baterai $chargingText."
            HealthLevel.UNAVAILABLE -> "Data baterai atau pengisian belum tersedia."
        }
        return DeviceHealthItem(
            id = "battery_level",
            section = HealthSection.BATTERY,
            title = "Daya baterai",
            value = value,
            level = level,
            detail = detail
        )
    }

    private fun temperatureItem(temperatureC: Float?): DeviceHealthItem {
        val level = when {
            temperatureC == null -> HealthLevel.UNAVAILABLE
            temperatureC >= 45f -> HealthLevel.CRITICAL
            temperatureC >= 40f -> HealthLevel.WARNING
            else -> HealthLevel.GOOD
        }
        val value = temperatureC?.let {
            String.format(Locale.getDefault(), "%.1f°C", it)
        } ?: "Tidak tersedia"
        val detail = when (level) {
            HealthLevel.CRITICAL -> "Perangkat terlalu panas. Hentikan penggunaan berat dan lepaskan pengisi daya."
            HealthLevel.WARNING -> "Suhu perangkat mulai tinggi."
            HealthLevel.GOOD -> "Suhu baterai berada dalam rentang normal."
            HealthLevel.UNAVAILABLE -> "Sensor suhu baterai tidak memberikan data."
        }
        return DeviceHealthItem(
            id = "battery_temperature",
            section = HealthSection.BATTERY,
            title = "Suhu baterai",
            value = value,
            level = level,
            detail = detail
        )
    }

    private fun batteryConditionItem(condition: BatteryCondition): DeviceHealthItem {
        val level = when (condition) {
            BatteryCondition.GOOD -> HealthLevel.GOOD
            BatteryCondition.COLD -> HealthLevel.WARNING
            BatteryCondition.OVERHEAT,
            BatteryCondition.DEAD,
            BatteryCondition.OVER_VOLTAGE,
            BatteryCondition.FAILURE -> HealthLevel.CRITICAL
            BatteryCondition.UNKNOWN -> HealthLevel.UNAVAILABLE
        }
        val value = when (condition) {
            BatteryCondition.GOOD -> "Baik"
            BatteryCondition.OVERHEAT -> "Terlalu panas"
            BatteryCondition.DEAD -> "Rusak"
            BatteryCondition.OVER_VOLTAGE -> "Tegangan berlebih"
            BatteryCondition.COLD -> "Terlalu dingin"
            BatteryCondition.FAILURE -> "Bermasalah"
            BatteryCondition.UNKNOWN -> "Tidak tersedia"
        }
        return DeviceHealthItem(
            id = "battery_condition",
            section = HealthSection.BATTERY,
            title = "Kondisi baterai",
            value = value,
            level = level,
            detail = when (level) {
                HealthLevel.GOOD -> "Android melaporkan kondisi baterai baik."
                HealthLevel.WARNING -> "Suhu baterai terlalu dingin untuk kondisi ideal."
                HealthLevel.CRITICAL -> "Android mendeteksi masalah serius pada baterai."
                HealthLevel.UNAVAILABLE -> "Android tidak menyediakan status kondisi baterai."
            }
        )
    }

    private fun storageItem(availableBytes: Long?, totalBytes: Long?): DeviceHealthItem {
        val valid = availableBytes != null && totalBytes != null && availableBytes >= 0 && totalBytes > 0
        if (!valid) {
            return DeviceHealthItem(
                id = "storage",
                section = HealthSection.STORAGE,
                title = "Penyimpanan kosong",
                value = "Tidak tersedia",
                level = HealthLevel.UNAVAILABLE,
                detail = "Kapasitas penyimpanan tidak dapat dibaca.",
                action = DeviceHealthAction.STORAGE_SETTINGS
            )
        }

        val available = availableBytes!!
        val total = totalBytes!!
        val freePercent = available.toDouble() * 100.0 / total.toDouble()
        val level = when {
            available < GIB || freePercent < 5.0 -> HealthLevel.CRITICAL
            freePercent < 10.0 -> HealthLevel.WARNING
            else -> HealthLevel.GOOD
        }
        val value = String.format(
            Locale.getDefault(),
            "%.1f GB (%.1f%%)",
            available.toDouble() / GIB,
            max(0.0, freePercent)
        )
        return DeviceHealthItem(
            id = "storage",
            section = HealthSection.STORAGE,
            title = "Penyimpanan kosong",
            value = value,
            level = level,
            detail = when (level) {
                HealthLevel.CRITICAL -> "Ruang hampir habis dan dapat mengganggu aplikasi."
                HealthLevel.WARNING -> "Ruang kosong mulai menipis."
                HealthLevel.GOOD -> "Ruang penyimpanan masih mencukupi."
                HealthLevel.UNAVAILABLE -> "Kapasitas penyimpanan tidak dapat dibaca."
            },
            action = if (level == HealthLevel.GOOD) null else DeviceHealthAction.STORAGE_SETTINGS
        )
    }

    private fun internetItem(validated: Boolean?): DeviceHealthItem = DeviceHealthItem(
        id = "internet",
        section = HealthSection.CONNECTION,
        title = "Akses internet",
        value = when (validated) {
            true -> "Terhubung"
            false -> "Terputus"
            null -> "Tidak tersedia"
        },
        level = when (validated) {
            true -> HealthLevel.GOOD
            false -> HealthLevel.CRITICAL
            null -> HealthLevel.UNAVAILABLE
        },
        detail = when (validated) {
            true -> "Android memvalidasi koneksi internet."
            false -> "Jaringan aktif tidak dapat mencapai internet."
            null -> "Status internet tidak dapat diperiksa."
        },
        action = if (validated == true) null else DeviceHealthAction.NETWORK_SETTINGS
    )

    private fun networkTypeItem(networkType: String?): DeviceHealthItem = DeviceHealthItem(
        id = "network_type",
        section = HealthSection.CONNECTION,
        title = "Jenis jaringan",
        value = networkType ?: "Tidak tersedia",
        level = if (networkType == null) HealthLevel.UNAVAILABLE else HealthLevel.GOOD,
        detail = if (networkType == null) {
            "Tidak ada jaringan aktif yang dapat dikenali."
        } else {
            "Perangkat menggunakan $networkType."
        }
    )

    private fun serviceItem(status: String?): DeviceHealthItem {
        val level = serviceLevel(status)
        return DeviceHealthItem(
            id = "secondlink_service",
            section = HealthSection.SYSTEM,
            title = "Layanan SecondLink",
            value = status ?: "Tidak tersedia",
            level = level,
            detail = when (level) {
                HealthLevel.GOOD -> "Layanan remote terhubung."
                HealthLevel.WARNING -> "Layanan remote tidak sedang aktif penuh."
                HealthLevel.CRITICAL -> "Layanan remote mengalami kegagalan."
                HealthLevel.UNAVAILABLE -> "Status layanan belum dapat dikenali."
            }
        )
    }

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

    private fun uptimeItem(uptimeMillis: Long?): DeviceHealthItem {
        val validUptime = uptimeMillis?.takeIf { it >= 0 }
        val value = validUptime?.let {
            val totalMinutes = it / 60_000L
            val days = totalMinutes / (24L * 60L)
            val hours = (totalMinutes / 60L) % 24L
            val minutes = totalMinutes % 60L
            if (days > 0) "${days}h ${hours}j ${minutes}m" else "${hours}j ${minutes}m"
        } ?: "Tidak tersedia"
        return DeviceHealthItem(
            id = "uptime",
            section = HealthSection.SYSTEM,
            title = "Waktu aktif perangkat",
            value = value,
            level = if (validUptime == null) HealthLevel.UNAVAILABLE else HealthLevel.GOOD,
            detail = "Waktu sejak perangkat terakhir dinyalakan ulang."
        )
    }

    private fun accessItem(
        id: String,
        title: String,
        granted: Boolean?,
        action: DeviceHealthAction
    ): DeviceHealthItem = DeviceHealthItem(
        id = id,
        section = HealthSection.ACCESS,
        title = title,
        value = when (granted) {
            true -> "Aktif"
            false -> "Belum aktif"
            null -> "Tidak tersedia"
        },
        level = when (granted) {
            true -> HealthLevel.GOOD
            false -> HealthLevel.WARNING
            null -> HealthLevel.UNAVAILABLE
        },
        detail = when (granted) {
            true -> "Akses tersedia untuk SecondLink."
            false -> "Fitur terkait tidak dapat bekerja penuh tanpa akses ini."
            null -> "Status akses tidak dapat diperiksa."
        },
        action = if (granted == true) null else action
    )

    private fun batteryOptimizationItem(ignored: Boolean?): DeviceHealthItem = DeviceHealthItem(
        id = "battery_optimization",
        section = HealthSection.ACCESS,
        title = "Penghemat baterai",
        value = when (ignored) {
            true -> "Dikecualikan"
            false -> "Dibatasi"
            null -> "Tidak tersedia"
        },
        level = when (ignored) {
            true -> HealthLevel.GOOD
            false -> HealthLevel.WARNING
            null -> HealthLevel.UNAVAILABLE
        },
        detail = when (ignored) {
            true -> "SecondLink diizinkan tetap aktif di latar belakang."
            false -> "Android dapat menghentikan koneksi remote saat aplikasi tidak dibuka."
            null -> "Status penghemat baterai tidak dapat diperiksa."
        },
        action = if (ignored == true) null else DeviceHealthAction.BATTERY_SETTINGS
    )
}
