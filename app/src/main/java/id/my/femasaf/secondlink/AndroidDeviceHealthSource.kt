package id.my.femasaf.secondlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceHealthPlatformSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val temperatureC: Float?,
    val batteryCondition: BatteryCondition,
    val storageAvailableBytes: Long?,
    val storageTotalBytes: Long?,
    val networkType: String?,
    val internetValidated: Boolean?,
    val uptimeMillis: Long?,
    val cameraGranted: Boolean?,
    val contactsGranted: Boolean?,
    val notificationsGranted: Boolean?,
    val mediaAccessGranted: Boolean?,
    val batteryOptimizationIgnored: Boolean?
)

object DeviceHealthInputFactory {
    fun create(platform: DeviceHealthPlatformSnapshot, botStatus: String?): DeviceHealthInput {
        val validStorage = platform.storageAvailableBytes != null &&
            platform.storageAvailableBytes >= 0L &&
            platform.storageTotalBytes != null &&
            platform.storageTotalBytes > 0L
        return DeviceHealthInput(
            batteryPercent = platform.batteryPercent?.takeIf { it in 0..100 },
            charging = platform.charging,
            temperatureC = platform.temperatureC?.takeIf { it.isFinite() },
            batteryCondition = platform.batteryCondition,
            storageAvailableBytes = platform.storageAvailableBytes.takeIf { validStorage },
            storageTotalBytes = platform.storageTotalBytes.takeIf { validStorage },
            networkType = platform.networkType,
            internetValidated = platform.internetValidated,
            uptimeMillis = platform.uptimeMillis?.takeIf { it >= 0L },
            botStatus = botStatus,
            cameraGranted = platform.cameraGranted,
            contactsGranted = platform.contactsGranted,
            notificationsGranted = platform.notificationsGranted,
            mediaAccessGranted = platform.mediaAccessGranted,
            batteryOptimizationIgnored = platform.batteryOptimizationIgnored
        )
    }
}

class AndroidDeviceHealthSource(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(botStatus: String?): DeviceHealthInput = withContext(Dispatchers.Default) {
        DeviceHealthInputFactory.create(readPlatformSnapshot(), botStatus)
    }

    private fun readPlatformSnapshot(): DeviceHealthPlatformSnapshot {
        val battery = readBattery()
        val storage = readStorage()
        val network = readNetwork()
        return DeviceHealthPlatformSnapshot(
            batteryPercent = battery?.percent,
            charging = battery?.charging,
            temperatureC = battery?.temperatureC,
            batteryCondition = battery?.condition ?: BatteryCondition.UNKNOWN,
            storageAvailableBytes = storage?.availableBytes,
            storageTotalBytes = storage?.totalBytes,
            networkType = network?.type,
            internetValidated = network?.internetValidated,
            uptimeMillis = runCatching { SystemClock.elapsedRealtime() }.getOrNull(),
            cameraGranted = permissionGranted(Manifest.permission.CAMERA),
            contactsGranted = permissionGranted(Manifest.permission.READ_CONTACTS),
            notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionGranted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            },
            mediaAccessGranted = runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(appContext)
                    .contains(appContext.packageName)
            }.getOrNull(),
            batteryOptimizationIgnored = runCatching {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
            }.getOrNull()
        )
    }

    private fun readBattery(): BatteryValues? = runCatching {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        BatteryValues(
            percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null,
            charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            },
            temperatureC = if (temperatureTenths == Int.MIN_VALUE) null else temperatureTenths / 10f,
            condition = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> BatteryCondition.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryCondition.OVERHEAT
                BatteryManager.BATTERY_HEALTH_DEAD -> BatteryCondition.DEAD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryCondition.OVER_VOLTAGE
                BatteryManager.BATTERY_HEALTH_COLD -> BatteryCondition.COLD
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryCondition.FAILURE
                else -> BatteryCondition.UNKNOWN
            }
        )
    }.getOrNull()

    private fun readStorage(): StorageValues? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        StorageValues(
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes
        )
    }.getOrNull()

    private fun readNetwork(): NetworkValues? = runCatching {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivity.activeNetwork
            ?: return@runCatching NetworkValues("Tidak terhubung", false)
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
            ?: return@runCatching NetworkValues(null, false)
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data seluler"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Jaringan lain"
        }
        NetworkValues(
            type = type,
            internetValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
    }.getOrNull()

    private fun permissionGranted(permission: String): Boolean? = runCatching {
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()

    private data class BatteryValues(
        val percent: Int?,
        val charging: Boolean?,
        val temperatureC: Float?,
        val condition: BatteryCondition
    )

    private data class StorageValues(val availableBytes: Long, val totalBytes: Long)

    private data class NetworkValues(val type: String?, val internetValidated: Boolean)
}
