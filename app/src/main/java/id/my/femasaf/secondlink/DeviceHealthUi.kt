package id.my.femasaf.secondlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SecondLinkIndigo = Color(0xFF4A3C8C)
private val Ink = Color(0xFF1C1B1F)
private val MutedInk = Color(0xFF625B71)
private val Border = Color(0xFFE0E0E0)
private val ScreenSurface = Color(0xFFF8F9FA)

private data class HealthVisual(
    val label: String,
    val foreground: Color,
    val background: Color,
    val icon: ImageVector
)

private fun healthVisual(level: HealthLevel): HealthVisual = when (level) {
    HealthLevel.GOOD -> HealthVisual(
        label = "Baik",
        foreground = Color(0xFF1B5E20),
        background = Color(0xFFE8F5E9),
        icon = Icons.Default.CheckCircle
    )
    HealthLevel.WARNING -> HealthVisual(
        label = "Perlu Perhatian",
        foreground = Color(0xFF7A4300),
        background = Color(0xFFFFF3E0),
        icon = Icons.Default.Warning
    )
    HealthLevel.CRITICAL -> HealthVisual(
        label = "Kritis",
        foreground = Color(0xFFB71C1C),
        background = Color(0xFFFFEBEE),
        icon = Icons.Default.ErrorOutline
    )
    HealthLevel.UNAVAILABLE -> HealthVisual(
        label = "Tidak Tersedia",
        foreground = Color(0xFF37474F),
        background = Color(0xFFECEFF1),
        icon = Icons.Default.ErrorOutline
    )
}

@Composable
fun DeviceHealthSummaryCard(
    snapshot: DeviceHealthSnapshot?,
    isRefreshing: Boolean,
    error: String?,
    onOpen: () -> Unit
) {
    val visual = healthVisual(snapshot?.overallLevel ?: HealthLevel.UNAVAILABLE)
    val primaryIssue = snapshot?.primaryIssue
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Device Health",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    Spacer(Modifier.height(8.dp))
                    HealthStatusPill(visual)
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Buka detail kesehatan perangkat",
                    tint = SecondLinkIndigo
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    snapshot == null && isRefreshing -> "Memeriksa kondisi perangkat…"
                    snapshot == null && error != null -> "Pemeriksaan belum tersedia. Buka untuk mencoba lagi."
                    primaryIssue != null -> primaryIssue.detail
                    snapshot != null -> "Semua kondisi yang dapat diperiksa berada dalam keadaan baik."
                    else -> "Buka untuk memeriksa kondisi perangkat."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Ink
            )
            snapshot?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Diperiksa ${formatCheckedTime(it.checkedAtMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MutedInk
                )
            }
            if (isRefreshing) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SecondLinkIndigo,
                    trackColor = Color(0xFFE8E5F1)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHealthScreen(
    snapshot: DeviceHealthSnapshot?,
    isRefreshing: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DeviceHealthAction) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ScreenSurface,
        topBar = {
            TopAppBar(
                title = { Text("Device Health") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SecondLinkIndigo,
                    trackColor = Color(0xFFE8E5F1)
                )
                Spacer(Modifier.height(16.dp))
            }

            DeviceHealthOverview(snapshot, error)

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondLinkIndigo),
                border = BorderStroke(1.dp, SecondLinkIndigo),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (isRefreshing) "Sedang memeriksa…" else "Periksa Sekarang")
            }

            if (snapshot != null) {
                Spacer(Modifier.height(24.dp))
                HealthSection.values().forEachIndexed { index, section ->
                    DeviceHealthSection(
                        title = sectionTitle(section),
                        items = snapshot.items.filter { it.section == section },
                        onAction = onAction
                    )
                    if (index != HealthSection.values().lastIndex) {
                        Spacer(Modifier.height(20.dp))
                    }
                }
            } else if (!isRefreshing) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Belum ada data kesehatan. Tekan Periksa Sekarang untuk mencoba kembali.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DeviceHealthOverview(snapshot: DeviceHealthSnapshot?, error: String?) {
    val visual = healthVisual(snapshot?.overallLevel ?: HealthLevel.UNAVAILABLE)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(visual.background, RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                tint = visual.foreground,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = visual.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = visual.foreground
                )
                snapshot?.let {
                    Text(
                        text = "Diperiksa ${formatCheckedTime(it.checkedAtMillis)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = visual.foreground
                    )
                }
            }
        }
        val message = error ?: snapshot?.primaryIssue?.detail
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = visual.foreground)
        }
    }
}

@Composable
private fun DeviceHealthSection(
    title: String,
    items: List<DeviceHealthItem>,
    onAction: (DeviceHealthAction) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ink
    )
    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp)
    ) {
        items.forEachIndexed { index, item ->
            DeviceHealthRow(item, onAction)
            if (index != items.lastIndex) {
                HorizontalDivider(color = Border)
            }
        }
    }
}

@Composable
private fun DeviceHealthRow(
    item: DeviceHealthItem,
    onAction: (DeviceHealthAction) -> Unit
) {
    val visual = healthVisual(item.level)
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = Ink
                )
            }
            Spacer(Modifier.size(12.dp))
            HealthStatusPill(visual)
        }
        Spacer(Modifier.height(8.dp))
        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MutedInk)
        item.action?.let { action ->
            TextButton(
                onClick = { onAction(action) },
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text(actionLabel(action), color = SecondLinkIndigo, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HealthStatusPill(visual: HealthVisual) {
    Row(
        modifier = Modifier
            .background(visual.background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.foreground,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = visual.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = visual.foreground
        )
    }
}

private fun sectionTitle(section: HealthSection): String = when (section) {
    HealthSection.BATTERY -> "Baterai"
    HealthSection.STORAGE -> "Penyimpanan"
    HealthSection.CONNECTION -> "Koneksi"
    HealthSection.SYSTEM -> "Sistem"
    HealthSection.ACCESS -> "Akses Penting"
}

private fun actionLabel(action: DeviceHealthAction): String = when (action) {
    DeviceHealthAction.BATTERY_SETTINGS -> "Buka Pengaturan Baterai"
    DeviceHealthAction.STORAGE_SETTINGS -> "Kelola Penyimpanan"
    DeviceHealthAction.NETWORK_SETTINGS -> "Periksa Jaringan"
    DeviceHealthAction.APP_PERMISSIONS -> "Kelola Izin"
    DeviceHealthAction.MEDIA_ACCESS -> "Kelola Akses Media"
}

private fun formatCheckedTime(timestamp: Long): String = SimpleDateFormat(
    "HH:mm:ss",
    Locale.getDefault()
).format(Date(timestamp))

fun openDeviceHealthAction(context: Context, action: DeviceHealthAction) {
    val packageDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri()
    )
    val primary = when (action) {
        DeviceHealthAction.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        DeviceHealthAction.STORAGE_SETTINGS -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        DeviceHealthAction.NETWORK_SETTINGS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        DeviceHealthAction.APP_PERMISSIONS -> packageDetails
        DeviceHealthAction.MEDIA_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    if (context !is Activity) {
        primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        packageDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(packageDetails) }
}
