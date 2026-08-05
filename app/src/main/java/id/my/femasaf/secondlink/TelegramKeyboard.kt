package id.my.femasaf.secondlink

/**
 * State used when rendering a menu.  It intentionally contains presentation
 * facts only; package names, URLs, access codes, and other user input never
 * become part of callback data.
 */
data class MenuRenderState(
    val unlocked: Boolean = false,
    val mediaReady: Boolean = false,
    val mediaSessionCount: Int = 0,
    val selectedMediaSession: Int? = null
)

/** Small, versioned callback payloads. User input is deliberately never embedded. */
object TelegramKeyboard {
    const val VERSION = "v1"

    /** Flat actions are retained for compatibility with the first menu/router release. */
    val actions: Set<String> = setOf(
        "menu", "main", "status", "media", "camera", "device", "schedule", "file", "security",
        "back", "close", "play", "pause", "unlock", "lock"
    )

    /** Actions accepted after a scope (for example, `v1:media:next`). */
    val scopedActions: Map<String, Set<String>> = mapOf(
        "menu" to setOf("main", "status", "media", "camera", "device", "schedule", "file", "security", "close"),
        "media" to setOf(
            "status", "sessions", "list", "select", "play", "pause", "toggle", "next", "previous", "prev",
            "rewind", "fastforward", "ff", "seek_back", "seek_forward", "stop", "queue", "track", "search",
            "speed", "volume", "repeat", "shuffle", "actions", "custom", "back"
        ),
        "camera" to setOf("front", "back", "capture", "back_menu"),
        "device" to setOf("contacts", "wifi", "alarm_on", "alarm_off", "back"),
        "schedule" to setOf("list", "add", "remove", "reminder", "back"),
        "file" to setOf("download", "status", "cancel", "back"),
        "security" to setOf("unlock", "lock", "status", "back")
    )

    fun callback(action: String, nonce: String? = null): String {
        require(action in actions) { "Unsupported callback action" }
        return encode(listOf(action) + noncePart(nonce))
    }

    /** Builds a scoped callback such as `v1:media:queue`. */
    fun callback(scope: String, action: String, nonce: String? = null): String {
        // Preserve the original two-argument `(action, nonce)` API when the
        // second value is a safe nonce rather than a scoped action.
        if (nonce == null && scope in actions && action.length <= 16 &&
            action.isNotEmpty() && action.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return encode(listOf(scope) + noncePart(action))
        }
        val known = action in (scopedActions[scope] ?: emptySet()) ||
            (scope == "media" && action.matches(Regex("select_[1-8]")))
        require(known) { "Unsupported callback action" }
        return encode(listOf(scope, action) + noncePart(nonce))
    }

    fun main(nonce: String? = null): TelegramReplyMarkup = render(MenuScreen.MAIN, MenuRenderState(), nonce)

    fun screen(screen: MenuScreen, nonce: String? = null): TelegramReplyMarkup =
        render(screen, MenuRenderState(), nonce)

    fun render(screen: MenuScreen, state: MenuRenderState, nonce: String? = null): TelegramReplyMarkup = when (screen) {
        MenuScreen.MAIN -> rows(
            button("Status", callback("status", nonce)), button("Media", callback("media", nonce)),
            button("Kamera", callback("camera", nonce)), button("Perangkat", callback("device", nonce)),
            button("Jadwal", callback("schedule", nonce)), button("File", callback("file", nonce)),
            button("Keamanan", callback("security", nonce)), button("Tutup Menu", callback("close", nonce))
        )
        MenuScreen.STATUS -> rows(
            button("Refresh", callback("status", nonce)), button("Kembali", callback("back", nonce))
        )
        MenuScreen.MEDIA -> media(state, nonce)
        MenuScreen.CAMERA -> rows(
            button("Depan", callback("camera", "front", nonce)), button("Belakang", callback("camera", "back", nonce)),
            button("Kembali", callback("back", nonce))
        )
        MenuScreen.DEVICE -> rows(
            button("Kontak", callback("device", "contacts", nonce)), button("Scan Wi‑Fi", callback("device", "wifi", nonce)),
            button("Alarm ON", callback("device", "alarm_on", nonce)), button("Alarm OFF", callback("device", "alarm_off", nonce)),
            button("Kembali", callback("back", nonce))
        )
        MenuScreen.SCHEDULE -> rows(
            button("Daftar Jadwal", callback("schedule", "list", nonce)), button("Tambah Jadwal", callback("schedule", "add", nonce)),
            button("Hapus Jadwal", callback("schedule", "remove", nonce)), button("Pengingat Baru", callback("schedule", "reminder", nonce)),
            button("Kembali", callback("back", nonce))
        )
        MenuScreen.FILE -> rows(
            button("Mulai Download", callback("file", "download", nonce)), button("Status Download", callback("file", "status", nonce)),
            button("Batalkan", callback("file", "cancel", nonce)), button("Kembali", callback("back", nonce))
        )
        MenuScreen.SECURITY -> rows(
            button("Buka Akses", callback("security", "unlock", nonce)), button("Kunci Sekarang", callback("security", "lock", nonce)),
            button(if (state.unlocked) "Sesi: Terbuka" else "Sesi: Terkunci", callback("security", "status", nonce)),
            button("Kembali", callback("back", nonce))
        )
    }

    private fun media(state: MenuRenderState, nonce: String?): TelegramReplyMarkup {
        val sessionButtons = (1..state.mediaSessionCount.coerceIn(0, 8)).map { n ->
            button("Sesi $n", callback("media", "select_$n", nonce))
        }
        val controls = mutableListOf(
            button("Status", callback("media", "status", nonce)), button("Daftar Sesi", callback("media", "sessions", nonce)),
            button("▶️ Play", callback("media", "play", nonce)), button("⏸ Pause", callback("media", "pause", nonce)),
            button("⏯ Toggle", callback("media", "toggle", nonce)), button("⏮ Sebelumnya", callback("media", "previous", nonce)),
            button("⏭ Berikutnya", callback("media", "next", nonce)), button("⏹ Stop", callback("media", "stop", nonce)),
            button("↶ Rewind", callback("media", "rewind", nonce)), button("↷ Fast-forward", callback("media", "fastforward", nonce)),
            button("Seek −10 dtk", callback("media", "seek_back", nonce)), button("Seek +30 dtk", callback("media", "seek_forward", nonce)),
            button("Antrean", callback("media", "queue", nonce)), button("Pilih Track", callback("media", "track", nonce)),
            button("Cari", callback("media", "search", nonce)), button("Kecepatan", callback("media", "speed", nonce)),
            button("Volume", callback("media", "volume", nonce)), button("Repeat", callback("media", "repeat", nonce)),
            button("Shuffle", callback("media", "shuffle", nonce)), button("Aksi Tambahan", callback("media", "actions", nonce)),
            button("Custom", callback("media", "custom", nonce)),
        )
        controls += sessionButtons
        controls += button("Kembali", callback("back", nonce))
        return rows(*controls.toTypedArray())
    }

    private fun encode(parts: List<String>): String {
        val value = (listOf(VERSION) + parts).joinToString(":")
        require(value.toByteArray(Charsets.UTF_8).size <= 64) { "Callback data too long" }
        return value
    }

    private fun noncePart(nonce: String?): List<String> {
        if (nonce.isNullOrBlank()) return emptyList()
        require(nonce.length <= 16 && nonce.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "Invalid callback nonce" }
        return listOf(nonce)
    }

    private fun rows(vararg buttons: TelegramInlineKeyboardButton): TelegramReplyMarkup =
        TelegramReplyMarkup(buttons.toList().chunked(2))

    private fun button(text: String, data: String) = TelegramInlineKeyboardButton(text, data)
}
