package id.my.femasaf.secondlink

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREF_NAME = "bot_config"
    private const val KEY_TOKEN = "telegram_bot_token"
    private const val KEY_CHAT_ID = "telegram_chat_id"
    private const val KEY_LAST_UPDATE_ID = "last_telegram_update_id"
    private const val KEY_BOT_ENABLED = "bot_enabled"
    private const val KEY_WEB_SERVER_ENABLED = "web_server_enabled"
    private const val KEY_WEB_SERVER_PASSWORD = "web_server_password"
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

    fun isWebServerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WEB_SERVER_ENABLED, true)
    }

    fun setWebServerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_WEB_SERVER_ENABLED, enabled)
            .apply()
    }

    fun getWebServerPassword(context: Context): String {
        return SecureStore(context).get(KEY_WEB_SERVER_PASSWORD).ifEmpty { "admin123" }
    }

    fun setWebServerPassword(context: Context, password: String): Boolean {
        val clean = password.trim()
        if (!SecurityPolicy.isValidWebServerPassword(clean)) return false
        SecureStore(context).put(KEY_WEB_SERVER_PASSWORD, clean)
        return true
    }

    fun getPreferredMediaPackage(context: Context): String =
        getPrefs(context).getString(KEY_PREFERRED_MEDIA_PACKAGE, "").orEmpty()

    fun setPreferredMediaPackage(context: Context, packageName: String) {
        getPrefs(context).edit().putString(KEY_PREFERRED_MEDIA_PACKAGE, packageName).apply()
    }

    fun hasValidCredentials(context: Context): Boolean =
        SecurityPolicy.isValidToken(getToken(context)) &&
            SecurityPolicy.isValidChatId(getChatId(context))

    fun verifyWebServerPassword(context: Context, candidate: String): Boolean {
        val expected = getWebServerPassword(context)
        return candidate.isNotEmpty() && candidate == expected
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
