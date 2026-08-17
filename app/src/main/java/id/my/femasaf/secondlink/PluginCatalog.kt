package id.my.femasaf.secondlink

data class PluginTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: String,
    val yamlContent: String
)

object PluginCatalog {
    val templates = listOf(
        PluginTemplate(
            id = "server-watch",
            name = "server-watch",
            description = "Memantau website/server lokal tiap 1 menit. Jika down, kirim notifikasi Telegram & coba restart.",
            category = "Monitoring",
            icon = "🌐",
            yamlContent = """
name: server-watch
description: "Watchdog monitor status server lokal"

trigger:
  every: 1m

conditions:
  - http_unreachable:
      url: http://localhost:8000
      timeout: 5s

actions:
  - telegram:
      message: "⚠️ *Peringatan Server Down!*\nServer http://localhost:8000 tidak merespons pada {{time}}."
  - notification:
      title: "SecondLink Watchdog"
      body: "Server lokal unreachable pada {{time}}"
  - web_server:
      action: "start"
            """.trimIndent()
        ),
        PluginTemplate(
            id = "backup-home",
            name = "backup-home",
            description = "Otomatis backup folder DCIM/Dokumen ketika terhubung ke Wi-Fi Rumah dan baterai di atas 30%.",
            category = "Backup",
            icon = "🏠",
            yamlContent = """
name: backup-home
description: "Sinkronisasi foto/file saat tersambung ke Wi-Fi Rumah"

trigger:
  type: wifi_connected
  ssid: "Rumah"

conditions:
  - battery_above: 30
  - is_charging: true

actions:
  - telegram:
      message: "🔄 *Memulai Backup Otomatis*\nTerhubung ke Wi-Fi '{{wifi_ssid}}' dengan baterai {{battery_level}}%."
  - sync:
      source: /storage/emulated/0/DCIM
      destination: /storage/emulated/0/Backup_DCIM
  - telegram:
      message: "✅ *Backup Selesai*\nFile berhasil disinkronkan pada {{time}}."
            """.trimIndent()
        ),
        PluginTemplate(
            id = "battery-guardian",
            name = "battery-guardian",
            description = "Peringatan otomatis saat baterai tersisa di bawah 20% dan tidak sedang dicas.",
            category = "Battery & Health",
            icon = "🔋",
            yamlContent = """
name: battery-guardian
description: "Pengingat hemat daya baterai kritis"

trigger:
  type: battery_changed
  mode: low

conditions:
  - battery_below: 20
  - is_charging: false

actions:
  - telegram:
      message: "🪫 *Baterai Lemah ({{battery_level}}%)*\nSegera colokkan charger ke perangkat Anda."
  - notification:
      title: "Baterai Lemah!"
      body: "Tersisa {{battery_level}}%. Colokkan charger sekarang."
            """.trimIndent()
        ),
        PluginTemplate(
            id = "nightly-web-off",
            name = "nightly-web-off",
            description = "Otomatis mematikan local web server di malam hari (23:00 - 06:00) untuk menghemat daya dan keamanan.",
            category = "Security & Power",
            icon = "🌙",
            yamlContent = """
name: nightly-web-off
description: "Auto turn off Web Server saat malam hari"

trigger:
  every: 30m

conditions:
  - time_between: "23:00-06:00"

actions:
  - web_server:
      action: "stop"
  - telegram:
      message: "🌙 *Night Mode Active*\nWeb server dimatikan otomatis untuk keamanan dan hemat daya."
            """.trimIndent()
        ),
        PluginTemplate(
            id = "security-snapshot",
            name = "security-snapshot",
            description = "Trigger pengambilan foto kamera depan saat menerima perintah kustom atau mendeteksi koneksi asing.",
            category = "Security",
            icon = "📸",
            yamlContent = """
name: security-snapshot
description: "Ambil foto snapshot kamera depan & kirim Telegram"

trigger:
  type: command
  name: "snapshot"

actions:
  - command: "/camera f"
  - telegram:
      message: "📸 *Security Snapshot berhasil diambil pada {{time}}*"
            """.trimIndent()
        ),
        PluginTemplate(
            id = "webhook-alert",
            name = "webhook-alert",
            description = "Kirim HTTP POST Webhook ke server/Discord/HomeAssistant saat event tertentu terjadi.",
            category = "Integrasi",
            icon = "🔗",
            yamlContent = """
name: webhook-alert
description: "Kirim status perangkat ke webhook eksternal"

trigger:
  every: 1h

actions:
  - http_request:
      url: "https://httpbin.org/post"
      method: "POST"
      body: '{"device": "SecondLink", "battery": {{battery_level}}, "time": "{{time}}"}'
  - telegram:
      message: "📡 *Webhook status terkirim* (Baterai: {{battery_level}}%)"
            """.trimIndent()
        ),
        PluginTemplate(
            id = "class-auto-silencer",
            name = "class-auto-silencer",
            description = "Otomatis menghentikan media player selama jam perkuliahan di hari kerja.",
            category = "Kuliah",
            icon = "🎓",
            yamlContent = """
name: class-auto-silencer
description: "Stop pemutaran media saat waktu kuliah"

trigger:
  every: 15m

conditions:
  - day_is: [1, 2, 3, 4, 5]
  - time_between: "08:00-16:00"

actions:
  - media:
      action: "pause"
            """.trimIndent()
        )
    )

    fun findById(id: String): PluginTemplate? = templates.find { it.id.equals(id, ignoreCase = true) }
    fun findByName(name: String): PluginTemplate? = templates.find { it.name.equals(name, ignoreCase = true) }
}
