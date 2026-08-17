package id.my.femasaf.secondlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// --- DATA MODEL ---

sealed class PluginTrigger {
    data class Interval(val intervalMs: Long, val raw: String) : PluginTrigger()
    data class WifiConnected(val ssid: String? = null) : PluginTrigger()
    data class BatteryChanged(val mode: String) : PluginTrigger() // "low", "charging", "full"
    data class Schedule(val at: String, val days: List<Int>? = null) : PluginTrigger() // at: "08:00", days: [1,2,3,4,5]
    data class TelegramCommand(val commandName: String) : PluginTrigger()
    object Manual : PluginTrigger()
}

sealed class PluginCondition {
    data class BatteryAbove(val percent: Int) : PluginCondition()
    data class BatteryBelow(val percent: Int) : PluginCondition()
    data class IsCharging(val charging: Boolean) : PluginCondition()
    data class WifiSsid(val ssid: String) : PluginCondition()
    data class HttpUnreachable(val url: String, val timeoutMs: Long = 5000L) : PluginCondition()
    data class HttpReachable(val url: String, val timeoutMs: Long = 5000L) : PluginCondition()
    data class TimeBetween(val start: String, val end: String) : PluginCondition()
    data class DayIs(val days: List<Int>) : PluginCondition()
    data class FileExists(val path: String) : PluginCondition()
    data class CustomCondition(val key: String, val value: Any?) : PluginCondition()
}

sealed class PluginAction {
    data class Telegram(val message: String?, val photoPath: String? = null, val documentPath: String? = null) : PluginAction()
    data class Notification(val title: String, val body: String) : PluginAction()
    data class Command(val command: String) : PluginAction()
    data class HttpRequest(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeoutMs: Long = 10000L
    ) : PluginAction()
    data class Sync(val source: String, val destination: String) : PluginAction()
    data class WebServer(val action: String) : PluginAction() // "start", "stop", "restart"
    data class Media(val action: String, val value: String? = null) : PluginAction() // "play", "pause", "stop", "next", "previous", "volume"
}

data class ParsedPlugin(
    val name: String,
    val description: String = "",
    val trigger: PluginTrigger,
    val conditions: List<PluginCondition> = emptyList(),
    val actions: List<PluginAction> = emptyList()
)

data class PluginRunResult(
    val success: Boolean,
    val executedActionsCount: Int = 0,
    val message: String,
    val error: String? = null
)

// --- PARSER ---

object PluginParser {
    private const val TAG = "PluginParser"

    fun parse(yamlString: String): Result<ParsedPlugin> {
        return try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 50
            }
            val yaml = Yaml(SafeConstructor(options))
            val rawObj = yaml.load<Any>(yamlString) ?: return Result.failure(IllegalArgumentException("YAML kosong."))

            if (rawObj !is Map<*, *>) {
                return Result.failure(IllegalArgumentException("Struktur root YAML harus berupa Map/Dictionary."))
            }

            @Suppress("UNCHECKED_CAST")
            val root = rawObj as Map<String, Any?>

            val name = root["name"]?.toString()?.trim()
            if (name.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Attribut 'name' wajib diisi pada plugin YAML."))
            }

            val description = root["description"]?.toString()?.trim() ?: ""

            // Parse Trigger
            val triggerRaw = root["trigger"]
            val trigger = parseTrigger(triggerRaw)

            // Parse Conditions
            val conditionsRaw = root["conditions"]
            val conditions = parseConditions(conditionsRaw)

            // Parse Actions
            val actionsRaw = root["actions"]
            val actions = parseActions(actionsRaw)

            if (actions.isEmpty()) {
                return Result.failure(IllegalArgumentException("Plugin harus memiliki minimal 1 aksi di bagian 'actions'."))
            }

            Result.success(ParsedPlugin(name, description, trigger, conditions, actions))
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Gagal mem-parse YAML plugin", e) }
            Result.failure(IllegalArgumentException("Format YAML tidak valid: ${e.message}", e))
        }
    }

    private fun parseTrigger(raw: Any?): PluginTrigger {
        if (raw == null) return PluginTrigger.Manual
        if (raw is String) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("every ") || trimmed.startsWith("every:")) {
                val intervalStr = trimmed.removePrefix("every:").removePrefix("every ").trim()
                val ms = parseDuration(intervalStr)
                return PluginTrigger.Interval(ms, intervalStr)
            }
            return PluginTrigger.Manual
        }
        if (raw is Map<*, *>) {
            val type = raw["type"]?.toString()?.trim()?.lowercase()
            val every = raw["every"]?.toString()?.trim()
            if (!every.isNullOrBlank()) {
                val ms = parseDuration(every)
                return PluginTrigger.Interval(ms, every)
            }

            return when (type) {
                "wifi_connected", "wifi" -> {
                    val ssid = raw["ssid"]?.toString()?.trim()?.removeSurrounding("\"")
                    PluginTrigger.WifiConnected(if (ssid.isNullOrBlank()) null else ssid)
                }
                "battery_changed", "battery" -> {
                    val mode = raw["mode"]?.toString()?.trim()?.lowercase() ?: "low"
                    PluginTrigger.BatteryChanged(mode)
                }
                "schedule", "cron" -> {
                    val at = raw["at"]?.toString()?.trim() ?: "08:00"
                    val days = (raw["days"] as? List<*>)?.mapNotNull { it?.toString()?.toIntOrNull() }
                    PluginTrigger.Schedule(at, days)
                }
                "command", "telegram_command" -> {
                    val cmd = raw["name"]?.toString()?.trim() ?: raw["command"]?.toString()?.trim() ?: "custom"
                    PluginTrigger.TelegramCommand(cmd)
                }
                else -> PluginTrigger.Manual
            }
        }
        return PluginTrigger.Manual
    }

    private fun parseConditions(raw: Any?): List<PluginCondition> {
        val result = mutableListOf<PluginCondition>()
        if (raw == null) return result

        val items = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> listOf(raw)
            else -> emptyList()
        }

        for (item in items) {
            if (item is Map<*, *>) {
                for ((key, value) in item) {
                    val k = key?.toString()?.trim()?.lowercase() ?: continue
                    when (k) {
                        "battery_above" -> {
                            val num = value?.toString()?.toIntOrNull() ?: 0
                            result.add(PluginCondition.BatteryAbove(num))
                        }
                        "battery_below" -> {
                            val num = value?.toString()?.toIntOrNull() ?: 100
                            result.add(PluginCondition.BatteryBelow(num))
                        }
                        "is_charging", "charging" -> {
                            val bool = value?.toString()?.toBooleanStrictOrNull() ?: true
                            result.add(PluginCondition.IsCharging(bool))
                        }
                        "wifi_ssid", "ssid" -> {
                            val ssid = value?.toString()?.trim()?.removeSurrounding("\"") ?: ""
                            result.add(PluginCondition.WifiSsid(ssid))
                        }
                        "http_unreachable" -> {
                            val url = if (value is Map<*, *>) value["url"]?.toString() ?: "" else value?.toString() ?: ""
                            val timeout = (value as? Map<*, *>)?.get("timeout")?.toString()?.let { parseDuration(it) } ?: 5000L
                            result.add(PluginCondition.HttpUnreachable(url.trim(), timeout))
                        }
                        "http_reachable" -> {
                            val url = if (value is Map<*, *>) value["url"]?.toString() ?: "" else value?.toString() ?: ""
                            val timeout = (value as? Map<*, *>)?.get("timeout")?.toString()?.let { parseDuration(it) } ?: 5000L
                            result.add(PluginCondition.HttpReachable(url.trim(), timeout))
                        }
                        "time_between" -> {
                            val parts = value?.toString()?.split("-") ?: emptyList()
                            if (parts.size >= 2) {
                                result.add(PluginCondition.TimeBetween(parts[0].trim(), parts[1].trim()))
                            }
                        }
                        "day_is", "days" -> {
                            val list = when (value) {
                                is List<*> -> value.mapNotNull { it?.toString()?.toIntOrNull() }
                                is Number -> listOf(value.toInt())
                                else -> emptyList()
                            }
                            result.add(PluginCondition.DayIs(list))
                        }
                        "file_exists" -> {
                            result.add(PluginCondition.FileExists(value?.toString()?.trim() ?: ""))
                        }
                        else -> {
                            result.add(PluginCondition.CustomCondition(k, value))
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseActions(raw: Any?): List<PluginAction> {
        val result = mutableListOf<PluginAction>()
        if (raw == null) return result

        val items = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> listOf(raw)
            else -> emptyList()
        }

        for (item in items) {
            if (item is Map<*, *>) {
                for ((key, value) in item) {
                    val k = key?.toString()?.trim()?.lowercase() ?: continue
                    when (k) {
                        "telegram", "send_telegram" -> {
                            if (value is Map<*, *>) {
                                val msg = value["message"]?.toString()
                                val photo = value["photo"]?.toString()
                                val doc = value["document"]?.toString()
                                result.add(PluginAction.Telegram(msg, photo, doc))
                            } else {
                                result.add(PluginAction.Telegram(value?.toString()))
                            }
                        }
                        "notification", "notify" -> {
                            if (value is Map<*, *>) {
                                val title = value["title"]?.toString() ?: "SecondLink Plugin"
                                val body = value["body"]?.toString() ?: value["message"]?.toString() ?: ""
                                result.add(PluginAction.Notification(title, body))
                            } else {
                                result.add(PluginAction.Notification("SecondLink Plugin", value?.toString() ?: ""))
                            }
                        }
                        "command", "exec", "bot_command" -> {
                            result.add(PluginAction.Command(value?.toString()?.trim() ?: ""))
                        }
                        "http", "http_request" -> {
                            if (value is Map<*, *>) {
                                val url = value["url"]?.toString() ?: ""
                                val method = value["method"]?.toString()?.uppercase() ?: "GET"
                                val headers = (value["headers"] as? Map<*, *>)?.mapNotNull { (hk, hv) ->
                                    if (hk != null && hv != null) hk.toString() to hv.toString() else null
                                }?.toMap() ?: emptyMap()
                                val body = value["body"]?.toString()
                                val timeout = value["timeout"]?.toString()?.let { parseDuration(it) } ?: 10000L
                                result.add(PluginAction.HttpRequest(url, method, headers, body, timeout))
                            } else {
                                result.add(PluginAction.HttpRequest(value?.toString() ?: ""))
                            }
                        }
                        "sync", "backup" -> {
                            if (value is Map<*, *>) {
                                val src = value["source"]?.toString() ?: ""
                                val dst = value["destination"]?.toString() ?: ""
                                result.add(PluginAction.Sync(src, dst))
                            }
                        }
                        "web_server", "web" -> {
                            val act = if (value is Map<*, *>) value["action"]?.toString() ?: "start" else value?.toString() ?: "start"
                            result.add(PluginAction.WebServer(act.lowercase()))
                        }
                        "media" -> {
                            val act = if (value is Map<*, *>) value["action"]?.toString() ?: "toggle" else value?.toString() ?: "toggle"
                            val v = if (value is Map<*, *>) value["value"]?.toString() else null
                            result.add(PluginAction.Media(act.lowercase(), v))
                        }
                    }
                }
            }
        }
        return result
    }

    fun parseDuration(input: String): Long {
        val clean = input.trim().lowercase()
        return try {
            when {
                clean.endsWith("ms") -> clean.removeSuffix("ms").trim().toLong()
                clean.endsWith("s") -> clean.removeSuffix("s").trim().toLong() * 1000L
                clean.endsWith("m") -> clean.removeSuffix("m").trim().toLong() * 60L * 1000L
                clean.endsWith("h") -> clean.removeSuffix("h").trim().toLong() * 60L * 60L * 1000L
                clean.endsWith("d") -> clean.removeSuffix("d").trim().toLong() * 24L * 60L * 60L * 1000L
                else -> {
                    val num = clean.toLongOrNull() ?: 60L
                    num * 1000L
                }
            }
        } catch (_: Exception) {
            60_000L
        }
    }
}

// --- EXECUTION ENGINE ---

class PluginEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val onSendTelegramMessage: suspend (String) -> Unit = {},
    private val onSendLocalNotification: (String, String) -> Unit = { _, _ -> },
    private val onExecuteBotCommand: suspend (String) -> String = { "OK" },
    private val onWebServerAction: (String) -> Unit = {}
) {
    private val activeIntervalJobs = ConcurrentHashMap<String, Job>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private val _pluginEvents = MutableSharedFlow<PluginRunResult>(extraBufferCapacity = 64)
    val pluginEvents: SharedFlow<PluginRunResult> = _pluginEvents.asSharedFlow()

    fun start() {
        registerSystemReceivers()
        refreshAllIntervalPlugins()
    }

    fun stop() {
        activeIntervalJobs.values.forEach { it.cancel() }
        activeIntervalJobs.clear()
        unregisterSystemReceivers()
    }

    fun refreshAllIntervalPlugins() {
        activeIntervalJobs.values.forEach { it.cancel() }
        activeIntervalJobs.clear()

        scope.launch {
            val plugins = BotManager.getPluginsList().filter { it.isEnabled }
            for (entity in plugins) {
                val parsed = PluginParser.parse(entity.yamlContent).getOrNull() ?: continue
                if (parsed.trigger is PluginTrigger.Interval) {
                    scheduleIntervalPlugin(entity.id, entity.name, parsed.trigger.intervalMs, parsed)
                }
            }
        }
    }

    fun scheduleIntervalPlugin(pluginId: String, name: String, intervalMs: Long, parsed: ParsedPlugin) {
        activeIntervalJobs[pluginId]?.cancel()
        val safeInterval = intervalMs.coerceAtLeast(10_000L) // Minimum interval 10 seconds for device health

        val job = scope.launch {
            while (isActive) {
                delay(safeInterval)
                try {
                    val entity = BotManager.getPluginById(pluginId)
                    if (entity != null && entity.isEnabled) {
                        executePlugin(entity.id, parsed, triggerContext = mapOf("trigger" to "interval"))
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    Log.e("PluginEngine", "Error executing interval plugin $name", e)
                }
            }
        }
        activeIntervalJobs[pluginId] = job
    }

    fun onPluginUpdatedOrCreated(entity: PluginEntity) {
        activeIntervalJobs[entity.id]?.cancel()
        activeIntervalJobs.remove(entity.id)

        if (!entity.isEnabled) return

        val parsed = PluginParser.parse(entity.yamlContent).getOrNull() ?: return
        if (parsed.trigger is PluginTrigger.Interval) {
            scheduleIntervalPlugin(entity.id, entity.name, parsed.trigger.intervalMs, parsed)
        }
    }

    fun onPluginDeleted(pluginId: String) {
        activeIntervalJobs[pluginId]?.cancel()
        activeIntervalJobs.remove(pluginId)
    }

    suspend fun executePlugin(
        pluginId: String,
        parsed: ParsedPlugin,
        triggerContext: Map<String, Any?> = emptyMap(),
        forceRun: Boolean = false
    ): PluginRunResult {
        val startTime = System.currentTimeMillis()
        var contextMap = HashMap<String, Any?>(triggerContext).apply {
            put("plugin_name", parsed.name)
            put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(startTime)))
            put("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(startTime)))
            put("battery_level", getBatteryLevel())
            put("is_charging", isCharging())
            put("wifi_ssid", getCurrentWifiSsid() ?: "")
        }

        // 1. Check conditions (if not forced)
        if (!forceRun) {
            val conditionsMet = evaluateConditions(parsed.conditions, contextMap)
            if (!conditionsMet) {
                val msg = "Kondisi plugin tidak terpenuhi."
                BotManager.updatePluginStatus(pluginId, startTime, msg)
                return PluginRunResult(success = true, executedActionsCount = 0, message = msg)
            }
        }

        // 2. Execute actions
        var executedCount = 0
        try {
            for (action in parsed.actions) {
                executeAction(action, contextMap)
                executedCount++
            }
            val statusMsg = "Berhasil ($executedCount aksi dijalankan)"
            BotManager.updatePluginStatus(pluginId, startTime, statusMsg)
            val result = PluginRunResult(success = true, executedActionsCount = executedCount, message = statusMsg)
            _pluginEvents.tryEmit(result)
            return result
        } catch (e: Exception) {
            val errMsg = "Gagal: ${e.message}"
            BotManager.updatePluginStatus(pluginId, startTime, errMsg)
            val result = PluginRunResult(success = false, executedActionsCount = executedCount, message = errMsg, error = e.message)
            _pluginEvents.tryEmit(result)
            return result
        }
    }

    private suspend fun evaluateConditions(conditions: List<PluginCondition>, contextMap: MutableMap<String, Any?>): Boolean {
        for (c in conditions) {
            val passed = when (c) {
                is PluginCondition.BatteryAbove -> getBatteryLevel() >= c.percent
                is PluginCondition.BatteryBelow -> getBatteryLevel() <= c.percent
                is PluginCondition.IsCharging -> isCharging() == c.charging
                is PluginCondition.WifiSsid -> {
                    val currentSsid = getCurrentWifiSsid()?.lowercase() ?: ""
                    currentSsid.contains(c.ssid.lowercase())
                }
                is PluginCondition.HttpUnreachable -> {
                    val isReachable = checkHttpReachable(c.url, c.timeoutMs, contextMap)
                    !isReachable
                }
                is PluginCondition.HttpReachable -> {
                    checkHttpReachable(c.url, c.timeoutMs, contextMap)
                }
                is PluginCondition.TimeBetween -> {
                    isCurrentTimeBetween(c.start, c.end)
                }
                is PluginCondition.DayIs -> {
                    val dayOfWeek = currentDayOfWeek()
                    c.days.contains(dayOfWeek)
                }
                is PluginCondition.FileExists -> {
                    File(c.path).exists()
                }
                is PluginCondition.CustomCondition -> true
            }
            if (!passed) return false
        }
        return true
    }

    private suspend fun executeAction(action: PluginAction, contextMap: MutableMap<String, Any?>) {
        when (action) {
            is PluginAction.Telegram -> {
                val renderedMessage = action.message?.let { renderTemplate(it, contextMap) }
                if (!renderedMessage.isNullOrBlank()) {
                    onSendTelegramMessage(renderedMessage)
                }
            }
            is PluginAction.Notification -> {
                val title = renderTemplate(action.title, contextMap)
                val body = renderTemplate(action.body, contextMap)
                onSendLocalNotification(title, body)
            }
            is PluginAction.Command -> {
                val renderedCommand = renderTemplate(action.command, contextMap)
                val output = onExecuteBotCommand(renderedCommand)
                contextMap["last_command_output"] = output
            }
            is PluginAction.HttpRequest -> {
                executeHttpRequest(action, contextMap)
            }
            is PluginAction.Sync -> {
                executeMockOrLocalSync(action.source, action.destination)
            }
            is PluginAction.WebServer -> {
                onWebServerAction(action.action)
            }
            is PluginAction.Media -> {
                onExecuteBotCommand("/media ${action.action}${if (!action.value.isNullOrBlank()) " ${action.value}" else ""}")
            }
        }
    }

    private suspend fun executeHttpRequest(action: PluginAction.HttpRequest, contextMap: MutableMap<String, Any?>) = withContext(Dispatchers.IO) {
        val renderedUrl = renderTemplate(action.url, contextMap)
        val requestBuilder = Request.Builder().url(renderedUrl)

        action.headers.forEach { (k, v) ->
            requestBuilder.header(k, renderTemplate(v, contextMap))
        }

        if (action.method.equals("POST", ignoreCase = true) || action.method.equals("PUT", ignoreCase = true)) {
            val bodyContent = renderTemplate(action.body ?: "", contextMap)
            val requestBody = bodyContent.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            requestBuilder.method(action.method, requestBody)
        } else {
            requestBuilder.get()
        }

        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val bodyString = response.body?.string() ?: ""
            contextMap["response_code"] = response.code
            contextMap["response_body"] = bodyString
            contextMap["http_success"] = response.isSuccessful
        } catch (e: Exception) {
            contextMap["http_error"] = e.message ?: "Network error"
            throw e
        }
    }

    private fun executeMockOrLocalSync(source: String, destination: String) {
        val srcFile = File(source)
        if (srcFile.exists() && destination.startsWith("/")) {
            val dstFile = File(destination)
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                dstFile.parentFile?.mkdirs()
                srcFile.copyTo(dstFile, overwrite = true)
            }
        }
    }

    private suspend fun checkHttpReachable(url: String, timeoutMs: Long, contextMap: MutableMap<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder().url(url).head().build()
            val client = httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val response = client.newCall(request).execute()
            contextMap["status_code"] = response.code
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    private fun renderTemplate(template: String, values: Map<String, Any?>): String {
        var result = template
        for ((key, value) in values) {
            result = result.replace("{{$key}}", value?.toString() ?: "")
            result = result.replace("{{ $key }}", value?.toString() ?: "")
        }
        return result
    }

    // --- SYSTEM HELPERS ---

    private fun getBatteryLevel(): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 50
    }

    private fun isCharging(): Boolean {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getCurrentWifiSsid(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info: WifiInfo? = wifiManager.connectionInfo
        val ssid = info?.ssid?.removeSurrounding("\"")
        return if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) ssid else null
    }

    private fun currentDayOfWeek(): Int {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    private fun isCurrentTimeBetween(start: String, end: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val nowStr = sdf.format(Date())
            val now = sdf.parse(nowStr) ?: return false
            val s = sdf.parse(start) ?: return false
            val e = sdf.parse(end) ?: return false

            if (s.before(e)) {
                (now.after(s) || now == s) && (now.before(e) || now == e)
            } else {
                now.after(s) || now == s || now.before(e) || now == e
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun registerSystemReceivers() {
        try {
            // Wi-Fi Network Callback
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        scope.launch {
                            delay(1500) // allow DHCP & SSID to bind
                            val currentSsid = getCurrentWifiSsid()
                            val plugins = BotManager.getPluginsList().filter { it.isEnabled }
                            for (entity in plugins) {
                                val parsed = PluginParser.parse(entity.yamlContent).getOrNull() ?: continue
                                if (parsed.trigger is PluginTrigger.WifiConnected) {
                                    val targetSsid = parsed.trigger.ssid
                                    if (targetSsid == null || (currentSsid != null && currentSsid.contains(targetSsid, ignoreCase = true))) {
                                        executePlugin(entity.id, parsed, mapOf("trigger" to "wifi_connected", "ssid" to (currentSsid ?: "")))
                                    }
                                }
                            }
                        }
                    }
                }
                cm.registerNetworkCallback(request, networkCallback!!)
            }

            // Battery state broadcast receiver
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    if (action == Intent.ACTION_BATTERY_LOW || action == Intent.ACTION_POWER_CONNECTED) {
                        scope.launch {
                            val isCharging = action == Intent.ACTION_POWER_CONNECTED
                            val plugins = BotManager.getPluginsList().filter { it.isEnabled }
                            for (entity in plugins) {
                                val parsed = PluginParser.parse(entity.yamlContent).getOrNull() ?: continue
                                if (parsed.trigger is PluginTrigger.BatteryChanged) {
                                    if ((parsed.trigger.mode == "low" && action == Intent.ACTION_BATTERY_LOW) ||
                                        (parsed.trigger.mode == "charging" && isCharging)) {
                                        executePlugin(entity.id, parsed, mapOf("trigger" to "battery_changed", "is_charging" to isCharging))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w("PluginEngine", "Could not register system receivers: ${e.message}")
        }
    }

    private fun unregisterSystemReceivers() {
        try {
            networkCallback?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
    }
}
