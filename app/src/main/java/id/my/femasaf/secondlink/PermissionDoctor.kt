package id.my.femasaf.secondlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionDoctor {
    fun report(context: Context): String {
        val checks = buildList {
            add(permission(context, "Kamera", Manifest.permission.CAMERA))
            add(permission(context, "Kontak", Manifest.permission.READ_CONTACTS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(permission(context, "Notifikasi", Manifest.permission.POST_NOTIFICATIONS))
            } else {
                add("✅ Notifikasi — diizinkan oleh versi Android")
            }
            add("✅ Internet — diizinkan saat instalasi")
            add("✅ Status jaringan — diizinkan saat instalasi")
            add(special("Kontrol media", NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)))
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            add(special("Berjalan tanpa optimasi baterai", powerManager.isIgnoringBatteryOptimizations(context.packageName)))
        }
        val allowed = checks.count { it.startsWith("✅") }
        val denied = checks.size - allowed
        return buildString {
            appendLine("🩺 *DOCTOR PERMISSION*")
            appendLine()
            checks.forEach(::appendLine)
            appendLine()
            append("Ringkasan: $allowed diizinkan, $denied belum diizinkan.")
            if (denied > 0) append("\nBuka aplikasi → Status Perangkat untuk memperbaiki akses.")
        }.trim()
    }

    private fun permission(context: Context, label: String, value: String): String =
        special(label, ContextCompat.checkSelfPermission(context, value) == PackageManager.PERMISSION_GRANTED)

    private fun special(label: String, granted: Boolean): String =
        if (granted) "✅ $label — diizinkan" else "❌ $label — tidak diizinkan"
}
