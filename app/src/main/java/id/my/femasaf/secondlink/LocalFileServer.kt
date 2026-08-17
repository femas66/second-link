package id.my.femasaf.secondlink

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalFileServer(private val context: Context, private val port: Int = DEFAULT_PORT) {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val loginAttempts = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val shareDir = (context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir)
        .resolve("SecondLinkShare")
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            shareDir.mkdirs()
            socket = ServerSocket(port).also { it.reuseAddress = true }
            running.set(true)
            acceptThread = Thread({ acceptLoop() }, "secondlink-file-server").apply { start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start local file server", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        acceptThread?.interrupt()
        workers.shutdownNow()
        sessions.clear()
    }

    fun address(): String? = localIpv4Address()?.let { "http://$it:$port" }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = socket?.accept() ?: break
                client.soTimeout = 30_000
                workers.execute { client.use(::handle) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Connection failed", e)
            }
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        try {
            if (!isPrivateOrLoopback(client.inetAddress.hostAddress.orEmpty())) {
                respondText(output, 403, "Akses hanya dari jaringan lokal.")
                return
            }
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3) return respondText(output, 400, "Permintaan tidak valid.")
            val method = parts[0]
            val target = parts[1]
            val headers = readHeaders(input) ?: return respondText(output, 431, "Header terlalu besar.")
            val path = target.substringBefore('?')
            val query = parseForm(target.substringAfter('?', ""))
            val session = session(headers["cookie"])

            when {
                method == "GET" && path == "/" && session == null -> respondHtml(output, loginPage())
                method == "POST" && path == "/login" -> login(client, input, output, headers)
                method == "GET" && path == "/logout" -> {
                    session?.let { sessions.remove(it.id) }
                    redirect(output, "/", "Set-Cookie: SLSESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict\r\n")
                }
                session == null -> redirect(output, "/")
                method == "GET" && path == "/" -> respondHtml(output, filesPage(session))
                method == "POST" && path == "/api/upload" -> upload(input, output, headers, query, session)
                method == "GET" && path == "/download" -> download(output, query["name"])
                else -> respondText(output, 404, "Tidak ditemukan.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
            runCatching { respondText(output, 500, "Terjadi kesalahan.") }
        } finally {
            runCatching { output.flush() }
        }
    }

    private fun login(client: Socket, input: BufferedInputStream, output: OutputStream, headers: Map<String, String>) {
        val ip = client.inetAddress.hostAddress.orEmpty()
        if (!allowLogin(ip)) return respondText(output, 429, "Terlalu banyak percobaan. Coba lagi nanti.")
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 1..4096) return respondText(output, 400, "Form login tidak valid.")
        val form = parseForm(String(readExactly(input, length), StandardCharsets.UTF_8))
        if (form["username"] != USERNAME || !Config.verifyWebServerPassword(context, form["password"].orEmpty())) {
            return respondHtml(output, loginPage("Nama pengguna atau kata sandi salah."), 401)
        }
        loginAttempts.remove(ip)
        val id = randomToken()
        sessions[id] = Session(id, randomToken(), System.currentTimeMillis() + SESSION_TTL_MS)
        redirect(output, "/", "Set-Cookie: SLSESSION=$id; Max-Age=3600; Path=/; HttpOnly; SameSite=Strict\r\n")
    }

    private fun upload(input: BufferedInputStream, output: OutputStream, headers: Map<String, String>, query: Map<String, String>, session: Session) {
        if (headers["x-csrf-token"] != session.csrf) return respondText(output, 403, "Token keamanan tidak valid.")
        val length = headers["content-length"]?.toLongOrNull() ?: -1
        if (length !in 1..MAX_UPLOAD_BYTES) return respondText(output, 413, "Ukuran file tidak valid atau melebihi 100 MB.")
        val name = safeName(query["name"].orEmpty()) ?: return respondText(output, 400, "Nama file tidak valid.")
        val destination = uniqueFile(name)
        try {
            destination.outputStream().buffered().use { sink -> copyExactly(input, sink, length) }
            respondText(output, 201, "File berhasil diunggah.")
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private fun download(output: OutputStream, rawName: String?) {
        val name = safeName(rawName.orEmpty()) ?: return respondText(output, 400, "Nama file tidak valid.")
        val file = shareDir.resolve(name)
        if (!file.isFile || file.parentFile?.canonicalFile != shareDir.canonicalFile) return respondText(output, 404, "File tidak ditemukan.")
        val encoded = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        writeHead(output, 200, "application/octet-stream", file.length(), "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\n")
        FileInputStream(file).use { it.copyTo(output) }
    }

    private fun filesPage(session: Session): String {
        val rows = shareDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            .joinToString("") { file ->
                val encoded = java.net.URLEncoder.encode(file.name, "UTF-8")
                "<li><span>${html(file.name)}</span><small>${formatSize(file.length())}</small><a href=\"/download?name=$encoded\">Download</a></li>"
            }.ifEmpty { "<li class=\"empty\">Belum ada file.</li>" }
        return page("File lokal", """
            <header><div><b>SecondLink Drive</b><p>Berbagi file lewat jaringan lokal</p></div><a class="muted" href="/logout">Keluar</a></header>
            <main><section class="upload"><h1>File Anda</h1><p>Pilih file hingga 100 MB. File disimpan hanya di perangkat ini.</p><input id="file" type="file"><button id="upload">Unggah file</button><progress id="progress" value="0" max="100" hidden></progress><p id="message"></p></section><ul>$rows</ul></main>
            <script>const b=document.getElementById('upload'),f=document.getElementById('file'),m=document.getElementById('message'),p=document.getElementById('progress');b.onclick=async()=>{if(!f.files[0]){m.textContent='Pilih file terlebih dahulu.';return}b.disabled=true;p.hidden=false;p.removeAttribute('value');m.textContent='Mengunggah…';try{const r=await fetch('/api/upload?name='+encodeURIComponent(f.files[0].name),{method:'POST',headers:{'Content-Type':'application/octet-stream','X-CSRF-Token':'${session.csrf}'},body:f.files[0]});m.textContent=await r.text();if(r.ok)location.reload()}catch(e){m.textContent='Upload gagal. Periksa koneksi.'}finally{b.disabled=false;p.hidden=true}};</script>
        """.trimIndent())
    }

    private fun loginPage(error: String? = null): String = page("Masuk", """
        <main class="login"><form method="post" action="/login"><b>SecondLink Drive</b><h1>Masuk</h1><p>Akses file perangkat melalui jaringan lokal.</p>${error?.let { "<div class=\"error\">${html(it)}</div>" }.orEmpty()}<label>Nama pengguna<input name="username" value="admin" autocomplete="username" required></label><label>Kata sandi<input name="password" type="password" autocomplete="current-password" required></label><button type="submit">Masuk</button></form></main>
    """.trimIndent())

    private fun page(title: String, body: String) = """<!doctype html><html lang="id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title · SecondLink</title><style>
        :root{font-family:system-ui,sans-serif;color:#17211b;background:#f3f5f1}*{box-sizing:border-box}body{margin:0}header,main{max-width:900px;margin:auto}header{padding:28px 20px;display:flex;justify-content:space-between;align-items:center}header p{margin:3px 0;color:#657068}.muted{color:#526259}.login{min-height:100vh;display:grid;place-items:center;padding:20px}form,.upload,ul{background:#fff;border:1px solid #dce2dc;border-radius:18px;padding:24px}form{width:min(420px,100%)}h1{margin:12px 0 4px;font-size:30px}p{color:#657068}label{display:block;margin-top:16px;font-size:14px}input{width:100%;margin-top:6px;padding:13px;border:1px solid #bdc8bf;border-radius:10px;font:inherit}button{margin-top:18px;padding:12px 18px;border:0;border-radius:10px;background:#245b3b;color:white;font-weight:700;cursor:pointer}button:disabled{opacity:.55}progress{display:block;width:100%;margin-top:16px}ul{list-style:none;padding:8px 20px;margin:16px 20px}li{display:grid;grid-template-columns:1fr auto auto;gap:16px;align-items:center;padding:15px 4px;border-bottom:1px solid #edf0ed}li:last-child{border:0}li a{color:#245b3b;font-weight:700}.empty{display:block;color:#657068}.upload{margin:0 20px}.upload input{background:#f7f9f6}.error{background:#fff0ef;color:#9f241f;padding:10px;border-radius:8px}small{color:#657068}@media(max-width:560px){li{grid-template-columns:1fr auto}li small{grid-column:1}header{padding-top:18px}}
    </style></head><body>$body</body></html>"""

    private fun session(cookie: String?): Session? {
        val id = cookie?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("SLSESSION=") }?.substringAfter('=') ?: return null
        val value = sessions[id] ?: return null
        if (value.expiresAt < System.currentTimeMillis()) { sessions.remove(id); return null }
        return value
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: return null
            total += line.length
            if (total > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return result
            val separator = line.indexOf(':')
            if (separator > 0) result[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray = ByteArray(length).also { bytes ->
        var offset = 0
        while (offset < length) { val read = input.read(bytes, offset, length - offset); if (read < 0) error("Body terputus"); offset += read }
    }

    private fun copyExactly(input: BufferedInputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("Body terputus")
            output.write(buffer, 0, count); remaining -= count
        }
    }

    private fun respondHtml(out: OutputStream, html: String, status: Int = 200) = respond(out, status, "text/html; charset=utf-8", html.toByteArray())
    private fun respondText(out: OutputStream, status: Int, text: String) = respond(out, status, "text/plain; charset=utf-8", text.toByteArray())
    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) { writeHead(out, status, type, body.size.toLong()); out.write(body) }
    private fun redirect(out: OutputStream, location: String, extra: String = "") = writeHead(out, 303, "text/plain", 0, "Location: $location\r\n$extra")
    private fun writeHead(out: OutputStream, status: Int, type: String, length: Long, extra: String = "") {
        val reason = mapOf(200 to "OK", 201 to "Created", 303 to "See Other", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 413 to "Payload Too Large", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error")[status] ?: "OK"
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private fun parseForm(value: String): Map<String, String> = value.split('&').filter { it.isNotEmpty() }.associate { field ->
        val pair = field.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }
    private fun safeName(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it.length <= 150 && it != "." && it != ".." && !it.contains('/') && !it.contains('\\') && it.none(Char::isISOControl) }
    private fun uniqueFile(name: String): File { val first = shareDir.resolve(name); if (!first.exists()) return first; val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }; var n = 2; while (true) { val f = shareDir.resolve("$base ($n)$ext"); if (!f.exists()) return f; n++ } }
    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun formatSize(bytes: Long): String = when { bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0); bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0); else -> "$bytes B" }
    private fun randomToken(): String = ByteArray(24).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun allowLogin(ip: String): Boolean { val now = System.currentTimeMillis(); val times = loginAttempts.getOrPut(ip) { ArrayDeque() }; synchronized(times) { while (times.isNotEmpty() && now - times.first() > LOGIN_WINDOW_MS) times.removeFirst(); if (times.size >= MAX_LOGIN_ATTEMPTS) return false; times.addLast(now); return true } }

    private data class Session(val id: String, val csrf: String, val expiresAt: Long)

    companion object {
        const val DEFAULT_PORT = 8080
        const val USERNAME = "admin"
        private const val TAG = "LocalFileServer"
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val SESSION_TTL_MS = 60L * 60 * 1000
        private const val LOGIN_WINDOW_MS = 15L * 60 * 1000
        private const val MAX_LOGIN_ATTEMPTS = 8

        fun localIpv4Address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && isPrivateOrLoopback(it.hostAddress.orEmpty()) }?.hostAddress
        }.getOrNull()

        internal fun isPrivateOrLoopback(ip: String): Boolean = ip == "127.0.0.1" || ip.startsWith("10.") || ip.startsWith("192.168.") || run {
            val parts = ip.split('.'); parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull() ?: -1) in 16..31
        }
    }
}
