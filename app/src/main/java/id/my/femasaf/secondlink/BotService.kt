package id.my.femasaf.secondlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import java.io.File
import java.util.Calendar
import java.util.UUID

class BotService : LifecycleService() {

    private var api: TelegramApi? = null
    private lateinit var cameraHelper: CameraHelper
    private lateinit var contactRepository: ContactRepository
    private lateinit var mediaControllerManager: MediaControllerManager
    private var mediaPlayer: MediaPlayer? = null
    private var lastUpdateId: Long = 0
    private var lastCronCheckMinute: Int = -1
    private var pollingJob: Job? = null
    private val startTime = System.currentTimeMillis()
    private val appVersion = "1.2.0"
    private val commandMutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()
    private val commandLastRun = mutableMapOf<String, Long>()
    private val menuState = TelegramMenuState()
    private lateinit var menuRouter: TelegramMenuRouter

    // Battery & Power Monitoring
    private var lastBatteryLevel: Int = -1
    private var lastChargingCheckLevel: Int = -1
    private var lastChargingCheckTime: Long = 0
    private var batteryAlert20Sent = false
    private var batteryAlert10Sent = false
    private var batteryAlert5Sent = false
    private var isPowerConnected = false
    private var isOverheatAlertSent = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
                Intent.ACTION_POWER_CONNECTED -> {
                    isPowerConnected = true
                    lifecycleScope.launch { sendTextMessage("🔌 Charger terhubung.") }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isPowerConnected = false
                    lifecycleScope.launch { sendTextMessage("⚠️ Charger terlepas!") }
                    lastChargingCheckLevel = -1
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        BotManager.init(this)
        cameraHelper = CameraHelper(this)
        contactRepository = ContactRepository(this, null)
        mediaControllerManager = MediaControllerManager(this)
        menuRouter = TelegramMenuRouter(menuState, object : CommandDispatcher {
            override suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult =
                dispatchMenuCommand(command, chatId, userId)
        }, { System.currentTimeMillis() },
            verifyAccessCode = { code -> Config.verifyAccessCode(this, code) },
            deleteCodeMessage = { chatId, messageId ->
                try { api?.deleteMessage(chatId, messageId)?.isSuccessful == true } catch (_: Exception) { false }
            }
        )
        
        lastUpdateId = Config.getLastUpdateId(this)
        
        startForegroundService()
        initBotClient()
        recoverReminders()

        // Register battery monitoring
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun initBotClient() {
        pollingJob?.cancel()
        val token = Config.getToken(this)
        if (!Config.hasValidCredentials(this) || !Config.hasAccessCode(this)) {
            api = null
            BotManager.setStatus("Configuration Required")
            updateNotification("Konfigurasi belum aman")
            return
        }

        api = Retrofit.Builder()
            .baseUrl("https://api.telegram.org/bot$token/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TelegramApi::class.java)
        
        contactRepository.updateApi(api!!)
        startPolling()
    }

    private fun recoverReminders() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pending = BotManager.getPendingReminders()
            val now = System.currentTimeMillis()
            for (reminder in pending) {
                val delayTime = reminder.targetTime - now
                if (delayTime > 0) {
                    launch {
                        delay(delayTime)
                        executeReminder(reminder.id, reminder.message)
                    }
                } else {
                    // Missed reminders while app was off
                    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    sendTextMessage("⏰ PENGINGAT TERLEWAT (${timeFormat.format(java.util.Date(reminder.targetTime))}): ${reminder.message}")
                    BotManager.markReminderDone(reminder.id)
                }
            }
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
        
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val isPlugged = plugged != 0
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        lifecycleScope.launch {
            if (!isCharging) {
                if (batteryPct <= 5 && !batteryAlert5Sent) {
                    sendTextMessage("‼️ *KRITIS:* Baterai tersisa $batteryPct%!")
                    batteryAlert5Sent = true
                } else if (batteryPct <= 10 && !batteryAlert10Sent) {
                    sendTextMessage("⚠️ *WARNING:* Baterai tersisa $batteryPct%!")
                    batteryAlert10Sent = true
                } else if (batteryPct <= 20 && !batteryAlert20Sent) {
                    sendTextMessage("📉 *INFO:* Baterai lemah ($batteryPct%).")
                    batteryAlert20Sent = true
                }
            } else {
                if (batteryPct > 20) batteryAlert20Sent = false
                if (batteryPct > 10) batteryAlert10Sent = false
                if (batteryPct > 5) batteryAlert5Sent = false
            }

            if (temperature >= 45.0) {
                if (!isOverheatAlertSent) {
                    sendTextMessage("🔥 *OVERHEAT:* Suhu HP sangat panas: $temperature°C!")
                    isOverheatAlertSent = true
                }
            } else if (temperature < 40.0) {
                isOverheatAlertSent = false
            }

            if (isPlugged) {
                if (!isCharging && status != BatteryManager.BATTERY_STATUS_FULL) {
                    sendTextMessage("❌ *PENGISIAN BERHENTI:* Kabel terhubung tapi daya tidak masuk!")
                } else if (isCharging && status != BatteryManager.BATTERY_STATUS_FULL) {
                    val currentTime = System.currentTimeMillis()
                    if (lastChargingCheckLevel == -1) {
                        lastChargingCheckLevel = batteryPct
                        lastChargingCheckTime = currentTime
                    } else {
                        val minutesElapsed = (currentTime - lastChargingCheckTime) / (1000 * 60)
                        if (minutesElapsed >= 20) {
                            if (batteryPct <= lastChargingCheckLevel) {
                                sendTextMessage("⚠️ *PENGISIAN BERMASALAH:* Baterai tidak bertambah selama 20 menit terakhir ($batteryPct%).")
                            }
                            lastChargingCheckLevel = batteryPct
                            lastChargingCheckTime = currentTime
                        }
                    }
                }
            } else {
                lastChargingCheckLevel = -1
            }
            lastBatteryLevel = batteryPct
        }
    }

    private fun startForegroundService() {
        val channelId = "bot_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "Telegram Bot Active", 
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi ini menunjukkan bahwa bot pemantau sedang aktif dan berjalan di latar belakang."
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bot Telegram Sedang Berjalan")
            .setContentText("Aplikasi sedang aktif memantau perintah dan kondisi perangkat.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(1, notification)
    }

    private fun updateNotification(status: String) {
        val channelId = "bot_service_channel"
        val manager = getSystemService(NotificationManager::class.java)
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bot Telegram: $status")
            .setContentText("Aplikasi aktif. Tap untuk membuka dashboard.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        manager.notify(1, notification)
    }

    private fun startPolling() {
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            BotManager.setStatus("Connected")
            updateNotification("Terhubung")
            while (true) {
                try {
                    val response = api?.getUpdates(offset = lastUpdateId + 1)
                    if (response?.isSuccessful == true) {
                        BotManager.setStatus("Connected")
                        val updates = response.body()?.result ?: emptyList()
                        for (update in updates) {
                            lastUpdateId = update.updateId
                            Config.saveLastUpdateId(this@BotService, lastUpdateId)
                            handleUpdate(update)
                        }
                    } else {
                        val code = response?.code() ?: -1
                        Log.e("BotService", "Polling error: $code")
                        BotManager.setStatus("Error $code")
                        updateNotification("Error $code")
                        BotManager.logError("API Error: $code")
                    }
                    checkCronJobs()
                } catch (e: java.net.SocketTimeoutException) {
                    // Expected
                } catch (e: Exception) {
                    Log.e("BotService", "Polling connection error", e)
                    BotManager.setStatus("Connection Error")
                    updateNotification("Gangguan Koneksi")
                    BotManager.logError("Network connection error")
                    delay(5000) // Wait before retry
                }
                delay(2000)
            }
        }
    }

    private suspend fun checkCronJobs() {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        if (currentMinute == lastCronCheckMinute) return
        lastCronCheckMinute = currentMinute

        val jobs = BotManager.cronJobs.value
        for (job in jobs) {
            if (job.hour == currentHour && job.minute == currentMinute) {
                executeInternalCommand(job.command)
            }
        }
    }

    private suspend fun executeInternalCommand(command: String) {
        val chatId = Config.getChatId(this)
        if (chatId.isEmpty()) return
        
        BotManager.audit("INTERNAL", command, "START")

        try {
            when {
                command == "/status" -> sendStatusUpdate(chatId)
                command.startsWith("/echo") -> {
                    val echoText = command.substringAfter("/echo").trim()
                    sendTextMessage(if (echoText.isNotEmpty()) echoText else "Cron Echo", chatId)
                }
                command == "/scan_wifi" -> scanLocalNetwork(chatId)
                command.startsWith("/camera") -> {
                    val isFront = command.contains(" f")
                    sendTextMessage("Cron: Mengambil foto...", chatId)
                    val file = cameraHelper.takePhoto(isFront, this)
                    if (file != null) sendPhoto(file)
                }
            }
            BotManager.audit("INTERNAL", command, "SUCCESS")
        } catch (e: Exception) {
            BotManager.audit("INTERNAL", command, "FAILED", e.message)
        }
    }

    private fun handleUpdate(update: TelegramUpdate) {
        update.callbackQuery?.let { callback ->
            lifecycleScope.launch { handleCallback(callback) }
            return
        }
        val message = update.message ?: return
        val originalText = message.text?.trim()?.take(4096) ?: return
        val chatId = message.chat.id.toString()
        val authorizedChatId = Config.getChatId(this)
        val messageAgeSeconds = (System.currentTimeMillis() / 1000L) - message.date

        if (!Config.hasValidCredentials(this) ||
            !Config.hasAccessCode(this) ||
            chatId != authorizedChatId ||
            message.chat.type != "private" ||
            messageAgeSeconds !in 0..MAX_COMMAND_AGE_SECONDS) {
            BotManager.audit("REDACTED", SecurityPolicy.redactCommand(originalText), "UNAUTHORIZED")
            return
        }

        lifecycleScope.launch {
            commandMutex.withLock {
                val senderId = message.from?.id ?: 0L
                if (menuState.hasPendingInput(chatId, senderId)) {
                    val result = menuRouter.acceptTextInput(message)
                    when (result) {
                        is RouterResult.SendMessage -> sendTextMessage(result.text, chatId)
                        is RouterResult.Answer -> sendTextMessage(result.text ?: "Aksi selesai.", chatId)
                        else -> Unit
                    }
                    return@withLock
                }
                val commandName = SecurityPolicy.commandName(originalText)
                if (!allowRequest(commandName)) {
                    sendTextMessage("⏳ Terlalu banyak perintah. Coba lagi sebentar.", chatId)
                    BotManager.audit(chatId, commandName, "RATE_LIMITED")
                    return@withLock
                }

                val sensitive = commandName in SENSITIVE_COMMANDS
                val text = if (sensitive) {
                    val suppliedCode = originalText.substringAfterLast(" #", "")
                    if (suppliedCode.isEmpty() || !Config.verifyAccessCode(this@BotService, suppliedCode)) {
                        sendTextMessage("🔐 Kode akses tidak valid. Format: $commandName ... #kode", chatId)
                        BotManager.audit(chatId, commandName, "DENIED")
                        return@withLock
                    }
                    val deletionSucceeded = runCatching {
                        api?.deleteMessage(chatId, message.messageId)?.isSuccessful == true
                    }.getOrDefault(false)
                    if (!deletionSucceeded) {
                        sendTextMessage("🔐 Perintah dibatalkan karena kode tidak dapat dihapus dari chat.", chatId)
                        BotManager.audit(chatId, commandName, "SECRET_DELETE_FAILED")
                        return@withLock
                    }
                    originalText.substringBeforeLast(" #").trim()
                } else {
                    originalText
                }

                BotManager.audit(chatId, SecurityPolicy.redactCommand(text), "START")
                try {
                    var recognized = true
                    when {
                        text == "/menu" || text == "/start" -> sendMainMenu(chatId, message.from?.id ?: 0L)
                        text == "/help" -> sendHelp(chatId)
                        text == "/status" -> sendStatusUpdate(chatId)
                        text == "/media" || text.startsWith("/media ") -> handleMediaCommand(text, chatId)
                        text == "/scan_wifi" -> scanLocalNetwork(chatId)
                        text.startsWith("/download ") -> handleDownload(text.substringAfter("/download").trim(), chatId)
                        text.startsWith("/echo ") -> sendTextMessage(text.substringAfter("/echo").take(1000), chatId)
                        text.startsWith("/cron_add ") -> handleCronAdd(text, chatId)
                        text == "/cron_list" -> handleCronList(chatId)
                        text.startsWith("/cron_stop ") -> handleCronStop(text, chatId)
                        text == "/list_contact" -> {
                            contactRepository.fetchAndSendContacts()
                            BotManager.recordContact()
                        }
                        text == "/camera" || text == "/camera f" -> {
                            sendTextMessage("Mengambil foto...", chatId)
                            val photoFile = cameraHelper.takePhoto(text.endsWith(" f"), this@BotService)
                            if (photoFile != null) {
                                sendPhoto(photoFile)
                                BotManager.recordPhoto()
                            } else sendTextMessage("Gagal mengambil foto.", chatId)
                        }
                        text == "/find" -> { startAlarm(); sendTextMessage("Alarm ON!", chatId) }
                        text == "/stop" -> { stopAlarm(); sendTextMessage("Alarm OFF", chatId) }
                        text.startsWith("/remind ") -> handleReminderCommand(text, chatId)
                        else -> {
                            recognized = false
                            sendTextMessage("❓ Perintah tidak dikenal. Gunakan /help.", chatId)
                        }
                    }
                    BotManager.audit(chatId, SecurityPolicy.redactCommand(text), if (recognized) "SUCCESS" else "UNKNOWN")
                } catch (e: Exception) {
                    Log.e("BotService", "Command failed", e)
                    sendTextMessage("❌ Perintah gagal diproses.", chatId)
                    BotManager.audit(chatId, SecurityPolicy.redactCommand(text), "FAILED")
                }
            }
        }
    }

    /** Sends the single owner-bound command-center menu, or edits its existing message. */
    suspend fun sendMainMenu(chatId: String, userId: Long, messageId: Long? = null) {
        val apiRef = api ?: return
        if (!Config.hasValidCredentials(this) || !Config.hasAccessCode(this) || chatId != Config.getChatId(this)) return
        val renderState = MenuRenderState(
            unlocked = menuState.isUnlocked(chatId, userId),
            mediaReady = runCatching { mediaControllerManager.hasAccess() }.getOrDefault(false),
            mediaSessionCount = 0
        )
        val markup = Gson().toJson(TelegramKeyboard.render(MenuScreen.MAIN, renderState))
        val text = "SecondLink Remote Control\nStatus: Connected\nMedia: ${if (renderState.mediaReady) "Ready" else "Akses diperlukan"}\nSecurity: ${if (renderState.unlocked) "Unlocked" else "Locked"}"
        menuState.bindMenu(chatId, userId)
        if (messageId != null) {
            val edited = runCatching { apiRef.editMessageText(chatId, messageId, text, markup).isSuccessful }.getOrDefault(false)
            if (edited) return
        }
        runCatching { apiRef.sendMessage(chatId, text, replyMarkup = markup) }
    }

    private suspend fun handleCallback(callback: TelegramCallbackQuery) {
        val message = callback.message
        val chatId = message?.chat?.id?.toString()
        if (message == null || message.chat.type != "private" ||
            !Config.hasValidCredentials(this) || !Config.hasAccessCode(this) ||
            chatId == null || chatId != Config.getChatId(this)) {
            // Do not reveal whether credentials or chat authorization failed.
            runCatching { api?.answerCallbackQuery(callback.id, "Aksi tidak diizinkan.", true) }
            return
        }
        val result = try {
            commandMutex.withLock { menuRouter.handle(callback) }
        } catch (_: Exception) {
            RouterResult.Answer(callback.id, "Aksi tidak dapat diproses.", true)
        }
        try {
            when (result) {
                is RouterResult.EditMenu -> {
                    api?.editMessageText(
                        result.chatId,
                        result.messageId,
                        result.text,
                        result.replyMarkup?.let { Gson().toJson(it) }
                    )
                    api?.answerCallbackQuery(result.callbackId)
                }
                is RouterResult.SendMessage -> {
                    api?.sendMessage(
                        result.chatId,
                        result.text,
                        replyMarkup = result.replyMarkup?.let { Gson().toJson(it) }
                    )
                    api?.answerCallbackQuery(result.callbackId)
                }
                is RouterResult.Answer -> api?.answerCallbackQuery(result.callbackId, result.text, result.showAlert)
                RouterResult.Ignored -> Unit
            }
        } catch (_: Exception) {
            // Callback responses intentionally expose no transport or exception details.
            try {
                api?.answerCallbackQuery(callback.id, "Aksi tidak dapat diproses.", true)
            } catch (_: Exception) {
                // Ignore transport failures; Telegram will clear the callback spinner eventually.
            }
        }
    }

    /** Narrow adapter around existing bounded handlers; text parsing remains unchanged. */
    private suspend fun dispatchMenuCommand(command: String, chatId: String, userId: Long): CommandResult {
        return try {
            when {
                command == "/status" -> {
                    sendStatusUpdate(chatId)
                    CommandResult("Status perangkat diperbarui.")
                }
                command.startsWith("/media ") -> {
                    handleMediaCommand(command, chatId)
                    CommandResult("Kontrol media diproses.")
                }
                command == "/camera" || command == "/camera f" -> {
                    sendTextMessage("Mengambil foto...", chatId)
                    val photoFile = cameraHelper.takePhoto(command.endsWith(" f"), this@BotService)
                    if (photoFile != null) { sendPhoto(photoFile); BotManager.recordPhoto() }
                    CommandResult("Kamera diproses.")
                }
                command == "/list_contact" -> {
                    contactRepository.fetchAndSendContacts()
                    BotManager.recordContact()
                    CommandResult("Kontak diproses.")
                }
                command == "/cron_list" -> {
                    handleCronList(chatId)
                    CommandResult("Jadwal diperbarui.")
                }
                command == "/scan_wifi" -> {
                    scanLocalNetwork(chatId)
                    CommandResult("Pemindaian Wi‑Fi diproses.")
                }
                command == "/find" -> { startAlarm(); CommandResult("Alarm dinyalakan.") }
                command == "/stop" -> { stopAlarm(); CommandResult("Alarm dimatikan.") }
                else -> CommandResult("Aksi tidak tersedia.", success = false)
            }
        } catch (_: Exception) {
            CommandResult("❌ Perintah gagal diproses.", success = false)
        }
    }

    private suspend fun sendHelp(chatId: String) {
        sendTextMessage(
            """
                🤖 *Daftar Perintah*
                /status — diagnostik HP
                /help — bantuan
                /cron\_list — daftar jadwal

                🔐 Perintah sensitif wajib diakhiri `#kode`:
                /camera [f] #kode
                /list\_contact #kode
                /scan\_wifi #kode
                /download <https-url> #kode
                /find #kode
                /stop #kode
                /cron\_add <HH:mm> <cmd> #kode
                /cron\_stop <id> #kode
                /remind <waktu> <pesan> #kode
                /media <aksi> #kode — kontrol media lengkap
            """.trimIndent(),
            chatId
        )
    }

    private fun allowRequest(command: String): Boolean {
        val now = System.currentTimeMillis()
        while (requestTimes.isNotEmpty() && now - requestTimes.first() > RATE_WINDOW_MS) {
            requestTimes.removeFirst()
        }
        if (requestTimes.size >= MAX_REQUESTS_PER_WINDOW) return false
        val cooldown = if (command in HEAVY_COMMANDS) HEAVY_COMMAND_COOLDOWN_MS else COMMAND_COOLDOWN_MS
        val lastRun = commandLastRun[command] ?: 0L
        if (now - lastRun < cooldown) return false
        requestTimes.addLast(now)
        commandLastRun[command] = now
        return true
    }

    private suspend fun handleMediaCommand(text: String, chatId: String) {
        val body = text.removePrefix("/media").trim()
        if (body.isEmpty() || body == "help") {
            sendTextMessage(mediaControllerManager.mediaHelp(), chatId)
            return
        }
        val parts = body.split(Regex("\\s+"), limit = 2)
        val action = parts[0].lowercase(java.util.Locale.ROOT)
        val argument = parts.getOrNull(1)?.trim()
        val result = when (action) {
            "status" -> mediaControllerManager.status()
            "list" -> mediaControllerManager.listSessions()
            "select" -> argument?.let(mediaControllerManager::selectSession)
                ?: "❌ Gunakan `/media select <nomor>`."
            else -> mediaControllerManager.transport(action, argument)
        }
        sendTextMessage(result, chatId)
    }

    private suspend fun handleCronAdd(text: String, chatId: String) {
        if (BotManager.cronJobs.value.size >= MAX_CRON_JOBS) {
            sendTextMessage("❌ Batas jadwal tercapai.", chatId)
            return
        }
        val parts = text.substringAfter("/cron_add").trim().split(" ", limit = 2)
        if (parts.size == 2) {
            val time = parts[0].split(":")
            if (time.size == 2) {
                val h = time[0].toIntOrNull()
                val m = time[1].toIntOrNull()
                if (h != null && m != null && h in 0..23 && m in 0..59 &&
                    isAllowedCronCommand(parts[1])) {
                    BotManager.addCronJob(parts[1], h, m)
                    sendTextMessage("✅ CronJob ditambahkan: ${parts[1]} jam $h:$m", chatId)
                    return
                }
            }
        }
        sendTextMessage("❌ Format: /cron_add HH:mm /command", chatId)
    }

    private suspend fun handleCronList(chatId: String) {
        val jobs = BotManager.cronJobs.value
        if (jobs.isEmpty()) sendTextMessage("📭 Kosong.", chatId)
        else {
            val list = jobs.joinToString("\n") { "• `${it.id.take(4)}` | ${it.hour}:${it.minute} | ${it.command}" }
            sendTextMessage("🕒 *CronJobs:*\n$list", chatId)
        }
    }

    private suspend fun handleCronStop(text: String, chatId: String) {
        val idPart = text.substringAfter("/cron_stop").trim()
        val job = BotManager.cronJobs.value.find { it.id.startsWith(idPart) }
        if (job != null) {
            BotManager.removeCronJob(job.id)
            sendTextMessage("✅ Dihapus.", chatId)
        } else sendTextMessage("❌ ID tidak ditemukan.", chatId)
    }

    private fun handleReminderCommand(text: String, chatId: String) {
        val parts = text.split(" ", limit = 3)
        if (parts.size < 3) {
            lifecycleScope.launch { sendTextMessage("❌ Format: /remind <waktu><unit> <pesan>", chatId) }
            return
        }

        val millis = parseTimeToMillis(parts[1])
        if (millis <= 0 || millis > MAX_REMINDER_DELAY_MS || parts[2].length > 500) {
            lifecycleScope.launch { sendTextMessage("❌ Waktu tidak valid (s, m, h).", chatId) }
            return
        }

        val targetTime = System.currentTimeMillis() + millis
        val reminderId = UUID.randomUUID().toString()
        val reminderMessage = parts[2]
        
        BotManager.addReminder(reminderId, reminderMessage, targetTime)

        lifecycleScope.launch {
            sendTextMessage("✅ Pengingat disetel dalam ${parts[1]}.", chatId)
            delay(millis)
            executeReminder(reminderId, reminderMessage)
        }
    }

    private suspend fun executeReminder(id: String, message: String) {
        sendTextMessage("⏰ PENGINGAT: $message")
        BotManager.markReminderDone(id)
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        return try {
            val unit = timeStr.last().lowercaseChar()
            val value = timeStr.dropLast(1).toLong()
            when (unit) {
                's' -> value * 1000
                'm' -> value * 60 * 1000
                'h' -> value * 60 * 60 * 1000
                else -> -1
            }
        } catch (e: Exception) { -1 }
    }

    private suspend fun sendTextMessage(text: String, targetChatId: String? = null) {
        val apiRef = api ?: return
        try {
            val finalChatId = targetChatId ?: Config.getChatId(this)
            if (finalChatId.isEmpty()) return
            apiRef.sendMessage(finalChatId, text)
        } catch (e: Exception) {
            Log.e("BotService", "Error sending text", e)
        }
    }

    private suspend fun sendPhoto(file: File) {
        val apiRef = api ?: return
        try {
            val targetChatId = Config.getChatId(this)
            val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("photo", file.name, requestFile)
            val chatId = targetChatId.toRequestBody("text/plain".toMediaTypeOrNull())
            apiRef.sendPhoto(chatId, body)
        } catch (e: Exception) {
            Log.e("BotService", "Error sending photo", e)
        } finally {
            file.delete()
        }
    }

    private fun startAlarm() {
        try {
            stopAlarm()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@BotService, alarmUri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { Log.e("BotService", "Alarm error", e) }
    }

    private fun stopAlarm() {
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
    }

    private suspend fun sendStatusUpdate(targetChatId: String) {
        try {
            // 1. Power & Battery
            val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = (level * 100 / scale.toFloat()).toInt()
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val healthInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
            val health = when(healthInt) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Sehat"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Rusak"
                else -> "Normal"
            }

            // 2. Storage
            val stat = android.os.StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val gbAvailable = bytesAvailable / (1024f * 1024f * 1024f)

            // 3. Network & Internet
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            val locale = java.util.Locale.getDefault()
            
            var netName = "Unknown"
            var wifiSignal = ""
            var ipAddress = "Unknown"

            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo
                    if (wifiInfo != null) {
                        netName = wifiInfo.ssid.replace("\"", "")
                        val rssi = wifiInfo.rssi
                        val levelWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            wm.calculateSignalLevel(rssi)
                        } else {
                            @Suppress("DEPRECATION")
                            android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
                        }
                        wifiSignal = " (Sinyal: $levelWifi)"
                    } else {
                        netName = "WiFi"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val info = wm.connectionInfo
                    netName = info.ssid.replace("\"", "")
                    val rssi = info.rssi
                    @Suppress("DEPRECATION")
                    val levelWifi = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
                    wifiSignal = " (Sinyal: $levelWifi/4)"
                }
                
                if (netName == "<unknown ssid>") netName = "WiFi"
                
                val lp = cm.getLinkProperties(activeNet)
                ipAddress = lp?.linkAddresses?.find { it.address is java.net.Inet4Address }?.address?.hostAddress ?: "Unknown"
            } else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                netName = "Data Seluler"
                val lp = cm.getLinkProperties(activeNet)
                ipAddress = lp?.linkAddresses?.find { it.address is java.net.Inet4Address }?.address?.hostAddress ?: "Unknown"
            }

            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && 
                             caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            // 4. Uptime
            val uptimeMillis = android.os.SystemClock.elapsedRealtime()
            val upHours = uptimeMillis / (1000 * 60 * 60)
            val upMins = (uptimeMillis / (1000 * 60)) % 60
            
            val botStart = java.text.SimpleDateFormat("dd MMM, HH:mm", locale).format(java.util.Date(startTime))

            val statusText = """
                📊 *DIAGNOSTIK PERANGKAT*
                
                🔋 *Baterai:* $pct% ${if (isCharging) "⚡" else ""}
                🌡️ *Suhu/Health:* $temp°C / $health
                💾 *Penyimpanan:* ${String.format(locale, "%.2f", gbAvailable)} GB Tersedia
                🌐 *Jaringan:* $netName$wifiSignal
                📍 *Alamat IP:* $ipAddress
                📶 *Internet:* ${if (hasInternet) "Terhubung ✅" else "Gangguan ❌"}
                
                ⏱️ *Uptime HP:* ${upHours}j ${upMins}m
                🤖 *Bot Version:* v$appVersion
                🚀 *Bot Started:* $botStart
                
                *Status Polling:* Healthy
            """.trimIndent()
            
            sendTextMessage(statusText, targetChatId)
        } catch (e: Exception) { 
            Log.e("BotService", "Status error", e)
            sendTextMessage("❌ Gagal mengambil diagnostik lengkap.", targetChatId)
        }
    }

    private suspend fun scanLocalNetwork(targetChatId: String) {
        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val devices = mutableSetOf<String>()
        sendTextMessage("🔍 Scanning WiFi...", targetChatId)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) {}
            override fun onServiceFound(si: NsdServiceInfo) { devices.add("• ${si.serviceName} (${si.serviceType})") }
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStartDiscoveryFailed(s: String, e: Int) { nsdManager.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(s: String, e: Int) { nsdManager.stopServiceDiscovery(this) }
        }
        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(10000)
            nsdManager.stopServiceDiscovery(listener)
            sendTextMessage(if (devices.isEmpty()) "❌ Tidak ditemukan perangkat." else "✅ *Ditemukan:* \n${devices.joinToString("\n")}", targetChatId)
        } catch (e: Exception) { sendTextMessage("Pemindaian gagal.", targetChatId) }
    }

    private suspend fun handleDownload(url: String, targetChatId: String) {
        if (url.isEmpty()) {
            sendTextMessage("❌ Masukkan URL.", targetChatId)
            return
        }
        withContext(Dispatchers.IO) {
          try {
            val uri = SecurityPolicy.validateDownloadUrl(url)
            if (uri == null) {
                sendTextMessage("❌ URL ditolak. Hanya HTTPS publik pada port 443 yang diizinkan.", targetChatId)
                return@withContext
            }
            val host = uri.host ?: return@withContext
            val addresses = SecurityPolicy.resolvePublicAddresses(host)
            if (addresses.isEmpty()) {
                sendTextMessage("❌ Host tujuan tidak aman atau tidak dapat diverifikasi.", targetChatId)
                return@withContext
            }
            val fileName = SecurityPolicy.safeDownloadName(uri)
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> =
                        if (hostname.equals(host, ignoreCase = true)) addresses else emptyList()
                })
                .build()
            val request = Request.Builder().url(uri.toString()).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.isRedirect) {
                    sendTextMessage("❌ Server menolak unduhan atau mencoba redirect.", targetChatId)
                    return@withContext
                }
                val body = response.body ?: run {
                    sendTextMessage("❌ Respons unduhan kosong.", targetChatId)
                    return@withContext
                }
                if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                    sendTextMessage("❌ File melebihi batas 25 MB.", targetChatId)
                    return@withContext
                }
                val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val output = File(directory, fileName)
                body.byteStream().use { input ->
                    output.outputStream().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) {
                                output.delete()
                                sendTextMessage("❌ File melebihi batas 25 MB.", targetChatId)
                                return@withContext
                            }
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                sendTextMessage("✅ Download aman selesai: $fileName (${output.parent})", targetChatId)
            }
          } catch (e: Exception) {
              Log.e("BotService", "Secure download failed")
              sendTextMessage("❌ Unduhan gagal.", targetChatId)
          }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "RESTART_POLLING" -> initBotClient()
            "EXIT_APP" -> {
                Config.setBotEnabled(this, false)
                BotManager.setStatus("Disabled")
                stopForeground(true)
                stopSelf()
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        if (::cameraHelper.isInitialized) cameraHelper.shutdown()
        super.onDestroy()
    }

    private fun isAllowedCronCommand(command: String): Boolean {
        val clean = command.trim()
        return clean == "/status" ||
            clean == "/scan_wifi" ||
            clean == "/camera" ||
            clean == "/camera f" ||
            (clean.startsWith("/echo ") && clean.length <= 500)
    }

    companion object {
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private const val COMMAND_COOLDOWN_MS = 1_500L
        private const val HEAVY_COMMAND_COOLDOWN_MS = 15_000L
        private const val MAX_CRON_JOBS = 20
        private const val MAX_REMINDER_DELAY_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024
        private const val MAX_COMMAND_AGE_SECONDS = 300L

        private val SENSITIVE_COMMANDS = setOf(
            "/camera", "/list_contact", "/scan_wifi", "/download",
            "/find", "/stop", "/cron_add", "/cron_stop", "/remind", "/media"
        )
        private val HEAVY_COMMANDS = setOf(
            "/camera", "/list_contact", "/scan_wifi", "/download"
        )
    }
}
