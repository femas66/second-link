package id.my.femasaf.secondlink

import java.util.UUID

enum class MenuScreen { MAIN, STATUS, MEDIA, CAMERA, DEVICE, SCHEDULE, FILE, WEB, EVENT, CLASS_SCHEDULE, CLASS_SCHEDULE_DAYS, PLUGINS }

enum class InputKind {
    DOWNLOAD_URL, REMINDER_DURATION, REMINDER_MESSAGE, CRON_TIME, CRON_COMMAND,
    EVENT_TIME, EVENT_TITLE, EVENT_DELETE,
    JADWAL_DAY, JADWAL_TIME, JADWAL_DETAILS, JADWAL_DELETE,
    PLUGIN_ADD, PLUGIN_EDIT, PLUGIN_DELETE, PLUGIN_RUN
}

data class UnlockSession(val chatId: String, val userId: Long, val nonce: String, val expiresAt: Long)

data class PendingInput(
    val chatId: String,
    val userId: Long,
    val kind: InputKind,
    val expiresAt: Long,
    val values: Map<String, String> = emptyMap()
)

/** In-memory, owner-bound menu sessions. Nothing in this class is persisted. */
class TelegramMenuState(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object { const val SESSION_TTL_MS: Long = 5 * 60 * 1000L }

    private data class Owner(val chatId: String, val userId: Long)
    private val unlocks = mutableMapOf<Owner, UnlockSession>()
    private val inputs = mutableMapOf<Owner, PendingInput>()
    private val menuOwners = mutableSetOf<Owner>()

    /** Records the owner of the currently displayed menu. Kept in memory only. */
    @Synchronized
    fun bindMenu(chatId: String, userId: Long) {
        menuOwners.removeIf { it.chatId == chatId }
        menuOwners += Owner(chatId, userId)
    }

    @Synchronized
    fun isKnownOwner(chatId: String, userId: Long): Boolean {
        val owner = Owner(chatId, userId)
        return menuOwners.any { it.chatId == chatId } && owner in menuOwners ||
            unlocks.keys.any { it.chatId == chatId } && owner in unlocks ||
            inputs.keys.any { it.chatId == chatId } && owner in inputs
    }

    @Synchronized
    fun hasOwnerInChat(chatId: String): Boolean =
        menuOwners.any { it.chatId == chatId } ||
            unlocks.keys.any { it.chatId == chatId } ||
            inputs.keys.any { it.chatId == chatId }

    @Synchronized
    fun hasAnyOwner(): Boolean = menuOwners.isNotEmpty() || unlocks.isNotEmpty() || inputs.isNotEmpty()

    @Synchronized
    fun unlock(chatId: String, userId: Long, now: Long = clock()): Boolean {
        val owner = Owner(chatId, userId)
        unlocks[owner] = UnlockSession(chatId, userId, UUID.randomUUID().toString().replace("-", "").take(12), now + SESSION_TTL_MS)
        return true
    }

    @Synchronized
    fun isUnlocked(chatId: String, userId: Long, now: Long = clock()): Boolean {
        val owner = Owner(chatId, userId)
        val session = unlocks[owner] ?: return false
        if (session.expiresAt <= now) { unlocks.remove(owner); return false }
        return true
    }

    @Synchronized fun lock(chatId: String, userId: Long) { unlocks.remove(Owner(chatId, userId)) }

    @Synchronized
    fun beginInput(
        chatId: String,
        userId: Long,
        kind: InputKind,
        values: Map<String, String> = emptyMap(),
        now: Long = clock()
    ): PendingInput {
        val pending = PendingInput(chatId, userId, kind, now + SESSION_TTL_MS, values.toMap())
        inputs[Owner(chatId, userId)] = pending
        return pending
    }

    @Synchronized
    fun consumeInput(chatId: String, userId: Long, now: Long = clock()): PendingInput? {
        val owner = Owner(chatId, userId)
        val pending = inputs.remove(owner) ?: return null
        return if (pending.expiresAt > now) pending else null
    }

    /** Returns a non-expired pending flow without consuming it. */
    @Synchronized
    fun pendingInput(chatId: String, userId: Long, now: Long = clock()): PendingInput? {
        val owner = Owner(chatId, userId)
        val pending = inputs[owner] ?: return null
        if (pending.expiresAt <= now) {
            inputs.remove(owner)
            return null
        }
        return pending
    }

    @Synchronized
    fun hasPendingInput(chatId: String, userId: Long, now: Long = clock()): Boolean =
        pendingInput(chatId, userId, now) != null

    @Synchronized
    fun hasPendingInputInChat(chatId: String, now: Long = clock()): Boolean =
        inputs.keys.any { it.chatId == chatId && pendingInput(it.chatId, it.userId, now) != null }

    @Synchronized
    fun cancelInput(chatId: String, userId: Long) {
        inputs.remove(Owner(chatId, userId))
    }

    @Synchronized
    fun clear(chatId: String, userId: Long) {
        val owner = Owner(chatId, userId)
        unlocks.remove(owner)
        inputs.remove(owner)
        menuOwners.remove(owner)
    }

    @Synchronized
    fun purgeExpired(now: Long = clock()) {
        unlocks.entries.removeIf { it.value.expiresAt <= now }
        inputs.entries.removeIf { it.value.expiresAt <= now }
    }
}
