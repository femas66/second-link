package id.my.femasaf.secondlink

/** Result of a bounded command handler used by both text and callback adapters. */
data class CommandResult(
    val message: String? = null,
    val replyMarkup: TelegramReplyMarkup? = null,
    val success: Boolean = true
)

interface CommandDispatcher {
    suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult
}

sealed interface RouterResult {
    data class EditMenu(
        val callbackId: String,
        val chatId: String,
        val messageId: Long,
        val text: String,
        val replyMarkup: TelegramReplyMarkup? = null
    ) : RouterResult

    data class SendMessage(
        val callbackId: String,
        val chatId: String,
        val text: String,
        val replyMarkup: TelegramReplyMarkup? = null
    ) : RouterResult

    data class Answer(val callbackId: String, val text: String? = null, val showAlert: Boolean = false) : RouterResult
    data object Ignored : RouterResult
}

/** Validates short callbacks and translates only fixed IDs to existing commands. */
class TelegramMenuRouter(
    private val state: TelegramMenuState,
    private val dispatcher: CommandDispatcher,
    private val clock: () -> Long
) {
    companion object {
        private const val MAX_CALLBACK_AGE_SECONDS = 5 * 60L
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private const val COMMAND_COOLDOWN_MS = 1_500L
        private const val MAX_INPUT_LENGTH = 4096
        private const val MAX_REMINDER_MESSAGE_LENGTH = 500
        private const val MAX_REMINDER_DELAY_MS = 30L * 24 * 60 * 60 * 1000
        private val REMINDER_DURATION = Regex("\\d{1,8}[smh]", RegexOption.IGNORE_CASE)
        private val CRON_TIME = Regex("\\d{1,2}:\\d{2}")
        private val PUBLIC_FLAT = setOf("menu", "main", "status", "doctor", "web", "back", "close")
        private val SCOPES = TelegramKeyboard.scopedActions.keys
    }

    private data class Parsed(val scope: String?, val action: String, val nonce: String?)
    private val requestTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val lastRun = mutableMapOf<String, Long>()

    /** Handles a text message only when an owner-bound guided flow is pending. */
    suspend fun acceptTextInput(message: TelegramMessage): RouterResult {
        val chatId = message.chat.id.toString()
        val userId = message.from?.id ?: return RouterResult.Ignored
        val now = clock()
        if (!state.hasPendingInputInChat(chatId, now)) return RouterResult.Ignored
        val pending = state.consumeInput(chatId, userId, now)
            ?: return RouterResult.Answer("", "Aksi tidak diizinkan.", true)
        val text = message.text?.trim().orEmpty()
        if (text.equals("/cancel", ignoreCase = true) || text.equals("batal", ignoreCase = true)) {
            return cancelInput(chatId, userId)
        }
        if (text.isEmpty() || text.length > MAX_INPUT_LENGTH) {
            state.beginInput(chatId, userId, pending.kind, pending.values, now)
            return RouterResult.SendMessage("", chatId, "❌ Input tidak valid atau terlalu panjang. Coba lagi, atau kirim /cancel.")
        }

        return when (pending.kind) {
            InputKind.DOWNLOAD_URL -> {
                val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
                if (uri?.scheme?.equals("https", true) != true || uri.host.isNullOrBlank() ||
                    !uri.userInfo.isNullOrEmpty() || (uri.port != -1 && uri.port != 443)) {
                    retry(pending, chatId, userId, now, "❌ URL ditolak. Gunakan URL HTTPS publik (port 443).")
                } else dispatchInput("/download $text", chatId, userId)
            }
            InputKind.REMINDER_DURATION -> {
                if (!REMINDER_DURATION.matches(text) || parseDuration(text) <= 0L || parseDuration(text) > MAX_REMINDER_DELAY_MS) {
                    retry(pending, chatId, userId, now, "❌ Durasi tidak valid. Contoh: 10m, 2h, atau 30s.")
                } else {
                    state.beginInput(chatId, userId, InputKind.REMINDER_MESSAGE, mapOf("duration" to text), now)
                    RouterResult.SendMessage("", chatId, "🗓️ Kirim isi pengingat (maksimal 500 karakter), atau /cancel.")
                }
            }
            InputKind.REMINDER_MESSAGE -> {
                if (text.length > MAX_REMINDER_MESSAGE_LENGTH) {
                    retry(pending, chatId, userId, now, "❌ Pesan terlalu panjang (maksimal 500 karakter).")
                } else dispatchInput("/remind ${pending.values["duration"].orEmpty()} $text", chatId, userId)
            }
            InputKind.CRON_TIME -> {
                if (!CRON_TIME.matches(text) || !validCronTime(text)) {
                    retry(pending, chatId, userId, now, "❌ Waktu tidak valid. Gunakan format HH:mm.")
                } else {
                    state.beginInput(chatId, userId, InputKind.CRON_COMMAND, mapOf("time" to text), now)
                    RouterResult.SendMessage("", chatId, "🗓️ Kirim command yang diizinkan (contoh: /status atau /echo teks).")
                }
            }
            InputKind.CRON_COMMAND -> {
                if (!isAllowedCronCommand(text)) {
                    retry(pending, chatId, userId, now, "❌ Command tidak diizinkan.")
                } else dispatchInput("/cron_add ${pending.values["time"].orEmpty()} $text", chatId, userId)
            }
            InputKind.EVENT_TIME -> {
                val parsedEpoch = parseEventTime(text)
                if (parsedEpoch <= 0L) {
                    retry(pending, chatId, userId, now, "❌ Format waktu tidak valid. Gunakan format YYYY-MM-DD HH:mm (contoh: 2026-08-17 15:30) atau DD-MM-YYYY HH:mm.")
                } else {
                    state.beginInput(chatId, userId, InputKind.EVENT_TITLE, mapOf("time" to text), now)
                    RouterResult.SendMessage("", chatId, "📝 Kirim judul event (dan opsional catatan, contoh: Rapat Tim | Ruang Zoom), atau /cancel.")
                }
            }
            InputKind.EVENT_TITLE -> {
                if (text.length > 500) {
                    retry(pending, chatId, userId, now, "❌ Judul terlalu panjang (maksimal 500 karakter).")
                } else {
                    dispatchInput("/event_add ${pending.values["time"].orEmpty()} $text", chatId, userId)
                }
            }
            InputKind.EVENT_DELETE -> {
                dispatchInput("/event_delete $text", chatId, userId)
            }
            InputKind.JADWAL_DAY -> {
                val dayNumber = parseDayOfWeek(text)
                if (dayNumber == null) {
                    retry(pending, chatId, userId, now, "❌ Hari tidak valid. Masukkan nama hari (Senin, Selasa, Rabu, Kamis, Jumat, Sabtu, Minggu) atau nomor (1-7), atau /cancel.")
                } else {
                    state.beginInput(chatId, userId, InputKind.JADWAL_TIME, mapOf("day" to dayNumber.toString()), now)
                    RouterResult.SendMessage("", chatId, "⏰ Masukkan jam kuliah (contoh: `08:00-10:30` atau `08:00 10:30`), atau /cancel.")
                }
            }
            InputKind.JADWAL_TIME -> {
                val cleanTime = text.replace(" ", "-")
                if (!cleanTime.contains("-") || cleanTime.length < 9) {
                    retry(pending, chatId, userId, now, "❌ Format jam tidak valid. Contoh: `08:00-10:30`, atau /cancel.")
                } else {
                    state.beginInput(chatId, userId, InputKind.JADWAL_DETAILS, mapOf("day" to pending.values["day"].orEmpty(), "time" to cleanTime), now)
                    RouterResult.SendMessage("", chatId, "📚 Masukkan format: `Mata Kuliah | Ruangan [| Dosen opsional]`\nContoh: `Pemrograman Web | Lab Komputer 2 | Pak Budi`, atau /cancel.")
                }
            }
            InputKind.JADWAL_DETAILS -> {
                if (text.isEmpty() || text.length > 500) {
                    retry(pending, chatId, userId, now, "❌ Detail mata kuliah tidak boleh kosong atau lebih dari 500 karakter.")
                } else {
                    val day = pending.values["day"].orEmpty()
                    val time = pending.values["time"].orEmpty()
                    dispatchInput("/jadwal_add $day $time $text", chatId, userId)
                }
            }
            InputKind.JADWAL_DELETE -> {
                dispatchInput("/jadwal_delete $text", chatId, userId)
            }
        }
    }

    fun cancelInput(chatId: String, userId: Long): RouterResult {
        state.cancelInput(chatId, userId)
        return RouterResult.SendMessage("", chatId, "❎ Alur input dibatalkan.")
    }

    suspend fun handle(callback: TelegramCallbackQuery): RouterResult {
        val message = callback.message ?: return answer(callback, "Menu tidak tersedia.")
        val chatId = message.chat.id.toString()
        val userId = callback.from.id
        val now = clock()

        if (state.isKnownOwner(chatId, userId).not() && state.hasAnyOwner()) {
            return answer(callback, "Aksi tidak diizinkan.", true)
        }
        val age = now / 1000L - message.date
        if (message.date <= 0L || age !in 0..MAX_CALLBACK_AGE_SECONDS) {
            return answer(callback, "Menu kedaluwarsa. Buka menu baru.")
        }
        val rawData = callback.data
        if (rawData != null && !rawData.startsWith("${TelegramKeyboard.VERSION}:")) {
            return answer(callback, "Menu perlu diperbarui.")
        }
        val parsed = parse(rawData) ?: return answer(callback, "Aksi tidak valid.")
        // A first public menu callback establishes the in-memory owner binding;
        // subsequent callbacks from another user are rejected above.
        if (!state.hasAnyOwner()) state.bindMenu(chatId, userId)
        if (!allowRequest("$chatId:$userId", parsed.action, now)) {
            return answer(callback, "Terlalu banyak aksi. Coba lagi sebentar.")
        }

        if (parsed.scope == "file" && parsed.action == "cancel") return cancelInput(chatId, userId)

        if (parsed.action == "close") {
            state.clear(chatId, userId)
            return edit(callback, chatId, message.messageId, "Menu ditutup.", TelegramReplyMarkup(emptyList()))
        }
        if (parsed.action == "menu" || parsed.action == "main" || parsed.action == "back") {
            return edit(callback, chatId, message.messageId, mainText(), TelegramKeyboard.render(MenuScreen.MAIN, MenuRenderState()))
        }
        if (parsed.scope == "jadwal" && parsed.action == "days") {
            return edit(callback, chatId, message.messageId, screenTitle(MenuScreen.CLASS_SCHEDULE_DAYS), TelegramKeyboard.render(MenuScreen.CLASS_SCHEDULE_DAYS, MenuRenderState()))
        }
        if (parsed.scope == "jadwal" && parsed.action == "back") {
            return edit(callback, chatId, message.messageId, screenTitle(MenuScreen.CLASS_SCHEDULE), TelegramKeyboard.render(MenuScreen.CLASS_SCHEDULE, MenuRenderState()))
        }
        if (parsed.scope == "menu") {
            val screen = when (parsed.action) {
                "status" -> MenuScreen.STATUS
                "web" -> MenuScreen.WEB
                "event" -> MenuScreen.EVENT
                "jadwal" -> MenuScreen.CLASS_SCHEDULE
                "media" -> MenuScreen.MEDIA
                "camera" -> MenuScreen.CAMERA
                "device" -> MenuScreen.DEVICE
                "schedule" -> MenuScreen.SCHEDULE
                "file" -> MenuScreen.FILE
                else -> MenuScreen.MAIN
            }
            return edit(callback, chatId, message.messageId, screenTitle(screen), TelegramKeyboard.render(screen, MenuRenderState()))
        }
        if (parsed.scope == null && parsed.action in setOf("web", "event", "jadwal", "media", "camera", "device", "schedule", "file")) {
            val screen = when (parsed.action) {
                "web" -> MenuScreen.WEB
                "event" -> MenuScreen.EVENT
                "jadwal" -> MenuScreen.CLASS_SCHEDULE
                "media" -> MenuScreen.MEDIA
                "camera" -> MenuScreen.CAMERA
                "device" -> MenuScreen.DEVICE
                "schedule" -> MenuScreen.SCHEDULE
                else -> MenuScreen.FILE
            }
            return edit(callback, chatId, message.messageId, screenTitle(screen), TelegramKeyboard.render(screen, MenuRenderState()))
        }

        val command = commandFor(parsed) ?: return prompt(callback, chatId, parsed.scope, parsed.action)
        val result = try { dispatcher.dispatch(command, chatId, userId) }
            catch (_: Exception) { CommandResult("❌ Perintah gagal diproses.", success = false) }
        val markup = result.replyMarkup ?: if (parsed.scope == null && parsed.action == "status") {
            TelegramKeyboard.render(MenuScreen.STATUS, MenuRenderState())
        } else if (parsed.scope == "web" || (parsed.scope == null && parsed.action == "web")) {
            TelegramKeyboard.render(MenuScreen.WEB, MenuRenderState())
        } else if (parsed.scope == "event" || (parsed.scope == null && parsed.action == "event")) {
            TelegramKeyboard.render(MenuScreen.EVENT, MenuRenderState())
        } else if (parsed.scope == "jadwal" || (parsed.scope == null && parsed.action == "jadwal")) {
            TelegramKeyboard.render(MenuScreen.CLASS_SCHEDULE, MenuRenderState())
        } else null
        return edit(callback, chatId, message.messageId, result.message ?: "✅ Selesai.", markup)
    }

    private fun parse(data: String?): Parsed? {
        val parts = data?.split(':') ?: return null
        if (parts.size !in 2..4 || parts.first() != TelegramKeyboard.VERSION) return null
        val scopeOrAction = parts[1]
        if (parts.size == 2) {
            if (scopeOrAction !in TelegramKeyboard.actions) return null
            return Parsed(null, scopeOrAction, null)
        }
        if (parts.size == 3 && scopeOrAction in SCOPES && isScopedAction(scopeOrAction, parts[2])) {
            return Parsed(scopeOrAction, parts[2], null)
        }
        val nonce = parts[2]
        if (scopeOrAction !in TelegramKeyboard.actions || !validNonce(nonce)) return null
        if (parts.size == 3) return Parsed(null, scopeOrAction, nonce)
        val action = parts[2]
        if (scopeOrAction !in SCOPES || !isScopedAction(scopeOrAction, action) || !validNonce(parts[3])) return null
        return Parsed(scopeOrAction, action, parts[3])
    }

    private fun isScopedAction(scope: String, action: String): Boolean =
        action in (TelegramKeyboard.scopedActions[scope] ?: emptySet()) ||
            (scope == "media" && action.matches(Regex("select_[1-8]")))

    private fun isSensitive(parsed: Parsed): Boolean {
        if (parsed.action == "unlock") return false
        if (parsed.scope == "menu") return false
        if (parsed.scope == "security") return parsed.action != "status" && parsed.action != "unlock" && parsed.action != "back"
        return parsed.scope != null && parsed.scope != "menu" || parsed.action !in PUBLIC_FLAT
    }

    private fun commandFor(parsed: Parsed): String? {
        if (parsed.scope == null) return when (parsed.action) {
            "status" -> "/status"
            "doctor" -> "/doctor"
            "web" -> "/web"
            "event" -> "/events"
            "jadwal" -> "/jadwal"
            else -> null
        }
        return when (parsed.scope to parsed.action) {
            "web" to "status" -> "/web"
            "web" to "on" -> "/web on"
            "web" to "off" -> "/web off"
            "event" to "list" -> "/events"
            "jadwal" to "today" -> "/jadwal today"
            "jadwal" to "tomorrow" -> "/jadwal tomorrow"
            "jadwal" to "all" -> "/jadwal all"
            "jadwal" to "day_1" -> "/jadwal senin"
            "jadwal" to "day_2" -> "/jadwal selasa"
            "jadwal" to "day_3" -> "/jadwal rabu"
            "jadwal" to "day_4" -> "/jadwal kamis"
            "jadwal" to "day_5" -> "/jadwal jumat"
            "jadwal" to "day_6" -> "/jadwal sabtu"
            "jadwal" to "day_7" -> "/jadwal minggu"
            "media" to "status" -> "/media status"
            "media" to "sessions", "media" to "list" -> "/media list"
            "media" to "play", "media" to "pause", "media" to "toggle", "media" to "next",
            "media" to "previous", "media" to "prev", "media" to "rewind", "media" to "fastforward",
            "media" to "ff", "media" to "stop", "media" to "queue", "media" to "actions" -> "/media ${parsed.action}"
            "media" to "seek_back" -> "/media seek -10"
            "media" to "seek_forward" -> "/media seek +30"
            "camera" to "front" -> "/camera f"
            "camera" to "back", "camera" to "capture" -> "/camera"
            "device" to "contacts" -> "/list_contact"
            "device" to "alarm_on" -> "/find"
            "device" to "alarm_off" -> "/stop"
            "schedule" to "list" -> "/cron_list"
            else -> if (parsed.scope == "media" && parsed.action.startsWith("select_")) {
                "/media select ${parsed.action.removePrefix("select_")}"
            } else null
        }
    }

    private fun prompt(callback: TelegramCallbackQuery, chatId: String, scope: String?, action: String): RouterResult {
        val now = clock()
        when (scope to action) {
            "media" to "track" -> state.beginInput(chatId, callback.from.id, InputKind.CRON_COMMAND, mapOf("media" to "track"), now)
            "media" to "search" -> state.beginInput(chatId, callback.from.id, InputKind.CRON_COMMAND, mapOf("media" to "search"), now)
            "media" to "speed" -> state.beginInput(chatId, callback.from.id, InputKind.CRON_COMMAND, mapOf("media" to "speed"), now)
            "media" to "volume" -> state.beginInput(chatId, callback.from.id, InputKind.CRON_COMMAND, mapOf("media" to "volume"), now)
            "schedule" to "reminder" -> state.beginInput(chatId, callback.from.id, InputKind.REMINDER_DURATION, now = now)
            "schedule" to "add" -> state.beginInput(chatId, callback.from.id, InputKind.CRON_TIME, now = now)
            "event" to "add" -> state.beginInput(chatId, callback.from.id, InputKind.EVENT_TIME, now = now)
            "event" to "delete" -> state.beginInput(chatId, callback.from.id, InputKind.EVENT_DELETE, now = now)
            "jadwal" to "add" -> state.beginInput(chatId, callback.from.id, InputKind.JADWAL_DAY, now = now)
            "jadwal" to "delete" -> state.beginInput(chatId, callback.from.id, InputKind.JADWAL_DELETE, now = now)
            "file" to "download" -> state.beginInput(chatId, callback.from.id, InputKind.DOWNLOAD_URL, now = now)
        }
        val text = when (scope to action) {
            "media" to "track" -> "🎵 Kirim nomor track sebagai pesan."
            "media" to "search" -> "🎵 Kirim judul/artis untuk dicari."
            "media" to "speed" -> "🎵 Kirim kecepatan (0.5–3.0)."
            "media" to "volume" -> "🎵 Kirim volume (up, down, mute, unmute, max, atau 0–100)."
            "media" to "repeat", "media" to "shuffle", "media" to "custom" -> "🎵 Pilih mode/aksi dari pesan teks."
            "schedule" to "add", "schedule" to "remove", "schedule" to "reminder" -> "🗓️ Gunakan alur pesan terpandu untuk aksi ini."
            "event" to "add" -> "📅 Kirim tanggal & jam event (contoh: 2026-08-17 15:30 atau 17-08-2026 15:30), atau /cancel."
            "event" to "delete" -> "🗑️ Kirim nomor urut atau ID event yang ingin dihapus, atau /cancel."
            "jadwal" to "add" -> "🎓 Kirim hari kuliah (contoh: Senin, Selasa, dst., atau angka 1-7), atau /cancel."
            "jadwal" to "delete" -> "🗑️ Kirim nomor urut jadwal kuliah yang ingin dihapus, atau /cancel."
            "file" to "download" -> "📁 Kirim URL HTTPS untuk memulai download."
            else -> "Aksi belum tersedia."
        }
        return RouterResult.SendMessage(callback.id, chatId, text, TelegramKeyboard.render(MenuScreen.MAIN, MenuRenderState()))
    }

    private fun parseDayOfWeek(input: String): Int? {
        val clean = input.trim().lowercase()
        return when (clean) {
            "1", "senin", "mon", "monday" -> 1
            "2", "selasa", "tue", "tuesday" -> 2
            "3", "rabu", "wed", "wednesday" -> 3
            "4", "kamis", "thu", "thursday" -> 4
            "5", "jumat", "jum'at", "fri", "friday" -> 5
            "6", "sabtu", "sat", "saturday" -> 6
            "7", "minggu", "ahad", "sun", "sunday" -> 7
            else -> null
        }
    }

    private fun allowRequest(owner: String, action: String, now: Long): Boolean {
        val times = requestTimes.getOrPut(owner) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > RATE_WINDOW_MS) times.removeFirst()
        if (times.size >= MAX_REQUESTS_PER_WINDOW) return false
        val key = "$owner:$action"
        val previous = lastRun[key]
        if (previous != null && now - previous < COMMAND_COOLDOWN_MS) return false
        times.addLast(now); lastRun[key] = now
        return true
    }

    private fun validNonce(value: String): Boolean = value.length in 1..16 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    private suspend fun dispatchInput(command: String, chatId: String, userId: Long): RouterResult {
        val result = try { dispatcher.dispatch(command, chatId, userId) }
            catch (_: Exception) { CommandResult("❌ Perintah gagal diproses.", success = false) }
        return RouterResult.SendMessage("", chatId, result.message ?: "✅ Selesai.")
    }

    private fun retry(pending: PendingInput, chatId: String, userId: Long, now: Long, message: String): RouterResult {
        state.beginInput(chatId, userId, pending.kind, pending.values, now)
        return RouterResult.SendMessage("", chatId, message)
    }

    private fun parseDuration(value: String): Long = runCatching {
        val amount = value.dropLast(1).toLong()
        when (value.last().lowercaseChar()) { 's' -> amount * 1000; 'm' -> amount * 60_000; 'h' -> amount * 3_600_000; else -> -1 }
    }.getOrDefault(-1)

    private fun parseEventTime(input: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "dd-MM-yyyy HH:mm",
            "yyyy/MM/dd HH:mm",
            "dd/MM/yyyy HH:mm"
        )
        for (pattern in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).apply {
                    isLenient = false
                }
                val date = sdf.parse(input)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return -1L
    }

    private fun validCronTime(value: String): Boolean {
        val parts = value.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    private fun isAllowedCronCommand(value: String): Boolean {
        val clean = value.trim()
        return clean == "/status" || clean == "/camera" || clean == "/camera f" ||
            clean == "/events" || clean.startsWith("/jadwal") || (clean.startsWith("/echo ") && clean.length <= 500)
    }

    private fun answer(callback: TelegramCallbackQuery, text: String, alert: Boolean = false) = RouterResult.Answer(callback.id, text, alert)
    private fun edit(callback: TelegramCallbackQuery, chatId: String, messageId: Long, text: String, markup: TelegramReplyMarkup?) = RouterResult.EditMenu(callback.id, chatId, messageId, text, markup)
    private fun screenTitle(screen: MenuScreen) = when (screen) {
        MenuScreen.MAIN -> mainText()
        MenuScreen.CLASS_SCHEDULE -> "🎓 Jadwal Kuliah"
        MenuScreen.CLASS_SCHEDULE_DAYS -> "🔍 Pilih Hari Jadwal Kuliah"
        else -> "SecondLink Remote Control • ${screen.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }
    private fun mainText() = "SecondLink Remote Control"
}
