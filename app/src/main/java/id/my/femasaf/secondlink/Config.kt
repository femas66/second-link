package id.my.femasaf.secondlink

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREF_NAME = "bot_config"
    private const val KEY_TOKEN = "telegram_bot_token"
    private const val KEY_CHAT_ID = "telegram_chat_id"
    private const val KEY_LAST_UPDATE_ID = "last_telegram_update_id"
    private const val KEY_BOT_ENABLED = "bot_enabled"
    private const val KEY_ACCESS_CODE_HASH = "access_code_hash"
    private const val KEY_ACCESS_CODE_SALT = "access_code_salt"
    private const val KEY_PREFERRED_MEDIA_PACKAGE = "preferred_media_package"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getToken(context: Context): String {
        migrateLegacySecrets(context)
        return SecureStore(context).get(KEY_TOKEN)
    }

    fun getChatId(context: Context): String {
        migrateLegacySecrets(context)
        return SecureStore(context).get(KEY_CHAT_ID)
    }

    fun saveConfig(context: Context, token: String, chatId: String): Boolean {
        val cleanToken = token.trim()
        val cleanChatId = chatId.trim()
        if (!SecurityPolicy.isValidToken(cleanToken) || !SecurityPolicy.isValidChatId(cleanChatId)) {
            return false
        }
        SecureStore(context).put(KEY_TOKEN, cleanToken)
        SecureStore(context).put(KEY_CHAT_ID, cleanChatId)
        return true
    }

    fun getLastUpdateId(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATE_ID, 0L)
    }

    fun saveLastUpdateId(context: Context, id: Long) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_UPDATE_ID, id)
            .apply()
    }

    fun clearToken(context: Context) {
        SecureStore(context).remove(KEY_TOKEN)
    }

    fun isBotEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BOT_ENABLED, false)
    }

    fun setBotEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_BOT_ENABLED, enabled)
            .apply()
    }

    fun getPreferredMediaPackage(context: Context): String =
        getPrefs(context).getString(KEY_PREFERRED_MEDIA_PACKAGE, "").orEmpty()

    fun setPreferredMediaPackage(context: Context, packageName: String) {
        getPrefs(context).edit().putString(KEY_PREFERRED_MEDIA_PACKAGE, packageName).apply()
    }

    fun hasValidCredentials(context: Context): Boolean =
        SecurityPolicy.isValidToken(getToken(context)) &&
            SecurityPolicy.isValidChatId(getChatId(context))

    fun setAccessCode(context: Context, code: String): Boolean {
        if (!SecurityPolicy.isStrongAccessCode(code)) return false
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val hash = hashAccessCode(code, salt)
        SecureStore(context).put(KEY_ACCESS_CODE_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
        SecureStore(context).put(KEY_ACCESS_CODE_HASH, android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
        return true
    }

    fun hasAccessCode(context: Context): Boolean =
        SecureStore(context).get(KEY_ACCESS_CODE_HASH).isNotEmpty()

    fun verifyAccessCode(context: Context, candidate: String): Boolean {
        val store = SecureStore(context)
        val salt = runCatching {
            android.util.Base64.decode(store.get(KEY_ACCESS_CODE_SALT), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val expected = runCatching {
            android.util.Base64.decode(store.get(KEY_ACCESS_CODE_HASH), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(expected, hashAccessCode(candidate, salt))
    }

    private fun hashAccessCode(code: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(code.toCharArray(), salt, 120_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    private fun migrateLegacySecrets(context: Context) {
        val prefs = getPrefs(context)
        val legacyToken = prefs.getString(KEY_TOKEN, "").orEmpty()
        val legacyChatId = prefs.getString(KEY_CHAT_ID, "").orEmpty()
        if (legacyToken.isNotEmpty() || legacyChatId.isNotEmpty()) {
            val secureStore = SecureStore(context)
            if (legacyToken.isNotEmpty()) secureStore.put(KEY_TOKEN, legacyToken)
            if (legacyChatId.isNotEmpty()) secureStore.put(KEY_CHAT_ID, legacyChatId)
            prefs.edit().remove(KEY_TOKEN).remove(KEY_CHAT_ID).apply()
        }
    }
}
