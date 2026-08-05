package id.my.femasaf.secondlink

import android.net.Uri
import java.net.InetAddress
import java.util.Locale

object SecurityPolicy {
    private val tokenPattern = Regex("""^\d{6,12}:[A-Za-z0-9_-]{30,80}$""")
    private val chatIdPattern = Regex("""^-?\d{5,20}$""")
    private val safeFileNamePattern = Regex("""[^A-Za-z0-9._-]""")

    fun isValidToken(value: String): Boolean = tokenPattern.matches(value.trim())

    fun isValidChatId(value: String): Boolean =
        chatIdPattern.matches(value.trim()) && value.trim() != "0"

    fun isStrongAccessCode(value: String): Boolean =
        value.length in 8..64 &&
            value.any(Char::isLetter) &&
            value.any(Char::isDigit)

    fun commandName(text: String): String =
        text.trim().substringBefore(' ').take(40).lowercase(Locale.ROOT)

    fun redactCommand(text: String): String = commandName(text)

    fun safeDownloadName(uri: Uri): String {
        val candidate = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.take(80)
            ?.replace(safeFileNamePattern, "_")
            ?.trim('.', '_')
            .orEmpty()
        return candidate.ifEmpty { "download_${System.currentTimeMillis()}" }
    }

    fun validateDownloadUrl(rawUrl: String): Uri? {
        if (rawUrl.length !in 12..2048) return null
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty() || uri.port !in listOf(-1, 443)) return null
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return null
        if (isPrivateOrLocalAddress(host)) return null
        return uri
    }

    fun resolvePublicAddresses(host: String): List<InetAddress> {
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        return addresses.takeIf { list ->
            list.isNotEmpty() && list.none {
                it.isAnyLocalAddress ||
                    it.isLoopbackAddress ||
                    it.isLinkLocalAddress ||
                    it.isSiteLocalAddress ||
                    it.isMulticastAddress
            }
        } ?: emptyList()
    }

    private fun isPrivateOrLocalAddress(host: String): Boolean {
        val addresses = resolvePublicAddresses(host)
        return addresses.isEmpty() || addresses.any {
            it.isAnyLocalAddress ||
                it.isLoopbackAddress ||
                it.isLinkLocalAddress ||
                it.isSiteLocalAddress ||
                it.isMulticastAddress
        }
    }
}
