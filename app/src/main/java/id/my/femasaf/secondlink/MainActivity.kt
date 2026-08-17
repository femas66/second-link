package id.my.femasaf.secondlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import id.my.femasaf.secondlink.ui.theme.SecondLinkTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {

    private val viewModel: BotViewModel by viewModels()

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            checkBatteryOptimization()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        BotManager.init(this)
        BotManager.viewModel = viewModel
        
        setContent {
            SecondLinkTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onStopService = { stopBotService() },
                    onStartService = { startBotService() },
                    onExitApp = { exitFullApp() }
                )
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkBatteryOptimization()
        } else {
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        
        if (Config.isBotEnabled(this) && Config.hasValidCredentials(this)) {
            startBotService()
        } else {
            BotManager.setStatus("Disabled")
        }
    }

    private fun startBotService() {
        if (!Config.hasValidCredentials(this)) {
            BotManager.setStatus("Configuration Required")
            return
        }
        Config.setBotEnabled(this, true)
        val intent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBotService() {
        Config.setBotEnabled(this, false)
        val intent = Intent(this, BotService::class.java)
        stopService(intent)
        BotManager.setStatus("Disabled")
    }

    private fun exitFullApp() {
        val intent = Intent(this, BotService::class.java).apply {
            action = "EXIT_APP"
        }
        startService(intent)
        finishAffinity()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: BotViewModel,
    onStopService: () -> Unit,
    onStartService: () -> Unit,
    onExitApp: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showAddEvent by remember { mutableStateOf(false) }
    var showAddSchedule by remember { mutableStateOf(false) }
    var showDeviceHealth by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val isEnabled = uiState.stats.status != "Disabled" && uiState.stats.status != "Stopped"

    DisposableEffect(showDeviceHealth) {
        if (showDeviceHealth) viewModel.startDeviceHealthMonitoring()
        onDispose {
            if (showDeviceHealth) viewModel.stopDeviceHealthMonitoring()
        }
    }

    if (showDeviceHealth) {
        BackHandler { showDeviceHealth = false }
        DeviceHealthScreen(
            snapshot = uiState.deviceHealth.snapshot,
            isRefreshing = uiState.deviceHealth.isRefreshing,
            error = uiState.deviceHealth.error,
            onBack = { showDeviceHealth = false },
            onRefresh = viewModel::refreshDeviceHealth,
            onAction = { openDeviceHealthAction(context, it) }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF8F9FA),
        topBar = {
            TopAppBar(
                title = { Text("Bot Dashboard") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .padding(24.dp)
        ) {
            StatusCard(uiState.stats)

            Spacer(modifier = Modifier.height(16.dp))

            DeviceHealthSummaryCard(
                snapshot = uiState.deviceHealth.snapshot,
                isRefreshing = uiState.deviceHealth.isRefreshing,
                error = uiState.deviceHealth.error,
                onOpen = { showDeviceHealth = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            MediaAccessCard()

            Spacer(modifier = Modifier.height(16.dp))

            LocalFileServerCard(isEnabled)
            
            Spacer(modifier = Modifier.height(24.dp))

            ClassScheduleCard(
                schedules = uiState.classSchedules,
                onAddSchedule = { showAddSchedule = true },
                onDeleteSchedule = { viewModel.deleteClassSchedule(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            EventCalendarCard(
                events = uiState.events,
                onAddEvent = { showAddEvent = true },
                onDeleteEvent = { viewModel.deleteEvent(it) }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Pending Reminders",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFF1C1B1F)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            ReminderList(uiState.reminders)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "CronJobs (Scheduled)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color(0xFF1C1B1F)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            CronJobList(uiState.cronJobs) { viewModel.removeCronJob(it) }

            Spacer(modifier = Modifier.height(32.dp))

            if (isEnabled) {
                Button(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("EMERGENCY STOP (KILL BOT)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("START BOT SERVICE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onExitApp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = BorderStroke(1.dp, Color(0xFF616161)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("EXIT & CLOSE APP", color = Color(0xFF616161), fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showSettings) {
        val serverPassword = remember { Config.getWebServerPassword(viewModel.getApplication()) }
        SettingsDialog(
            initialToken = uiState.botToken,
            initialChatId = uiState.chatId,
            initialWebServerPassword = serverPassword,
            onDismiss = { showSettings = false },
            onSave = { token, chatId, webServerPassword ->
                if (viewModel.updateConfig(token, chatId, webServerPassword)) {
                    showSettings = false
                }
            }
        )
    }

    if (showAddEvent) {
        AddEventDialog(
            onDismiss = { showAddEvent = false },
            onSave = { title, desc, epoch ->
                viewModel.addEvent(title, desc, epoch)
                showAddEvent = false
            }
        )
    }

    if (showAddSchedule) {
        AddClassScheduleDialog(
            onDismiss = { showAddSchedule = false },
            onSave = { day, start, end, sub, room, lec ->
                viewModel.addClassSchedule(day, start, end, sub, room, lec)
                showAddSchedule = false
            }
        )
    }
}

@Composable
fun MediaAccessCard() {
    val context = LocalContext.current
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        ),
        border = BorderStroke(1.dp, if (enabled) Color(0xFF81C784) else Color(0xFFFFB74D))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (enabled) "Kontrol media siap" else "Akses kontrol media belum aktif",
                fontWeight = FontWeight.Bold
            )
            Text(
                if (enabled) "Telegram dapat mengontrol sesi media aktif di HP ini."
                else "Aktifkan Notification access agar Spotify, YouTube Music, dan pemutar lain dapat dikontrol.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ) {
                Text(if (enabled) "Kelola akses media" else "Aktifkan akses media")
            }
        }
    }
}

@Composable
fun LocalFileServerCard(serviceEnabled: Boolean) {
    val context = LocalContext.current
    var webServerEnabled by remember { mutableStateOf(Config.isWebServerEnabled(context)) }
    val address = remember(serviceEnabled, webServerEnabled) {
        if (serviceEnabled && webServerEnabled) {
            LocalFileServer.localIpv4Address()?.let { "http://$it:${LocalFileServer.DEFAULT_PORT}" }
        } else null
    }
    val password = remember { Config.getWebServerPassword(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (serviceEnabled && webServerEnabled) Color(0xFFEAF2EC) else Color(0xFFF5F5F5)
        ),
        border = BorderStroke(1.dp, if (serviceEnabled && webServerEnabled) Color(0xFFA9C5B0) else Color(0xFFE0E0E0))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SecondLink Drive (Web Server)", fontWeight = FontWeight.Bold)
                    Text(
                        if (webServerEnabled && serviceEnabled) "🟢 Server Aktif" else "🔴 Server Nonaktif",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (webServerEnabled && serviceEnabled) Color(0xFF2E7D32) else Color.Gray
                    )
                }
                Switch(
                    checked = webServerEnabled,
                    onCheckedChange = { isChecked ->
                        webServerEnabled = isChecked
                        Config.setWebServerEnabled(context, isChecked)
                        val action = if (isChecked) "START_WEB_SERVER" else "STOP_WEB_SERVER"
                        val intent = Intent(context, BotService::class.java).apply { this.action = action }
                        if (serviceEnabled) {
                            context.startService(intent)
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !serviceEnabled -> "Layanan bot tidak aktif. Jalankan bot service untuk mengaktifkan server."
                    !webServerEnabled -> "Web server dimatikan. Aktifkan saklar di atas atau kirim /web on via bot Telegram."
                    address == null -> "Hubungkan perangkat ke Wi-Fi untuk mendapatkan alamat server lokal."
                    else -> "$address\nUsername: admin · Password: $password"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (serviceEnabled && webServerEnabled && address != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    contextStartUrlSafely(context, address)
                }) { Text("Buka Drive") }
            }
        }
    }
}

private fun contextStartUrlSafely(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialToken: String,
    initialChatId: String,
    initialWebServerPassword: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var chatId by remember { mutableStateOf(initialChatId) }
    var webServerPassword by remember { mutableStateOf(initialWebServerPassword) }
    val validCredentials = SecurityPolicy.isValidToken(token) && SecurityPolicy.isValidChatId(chatId)
    val validPassword = SecurityPolicy.isValidWebServerPassword(webServerPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bot & Web Server Configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Telegram Bot Token") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    isError = token.isNotEmpty() && !SecurityPolicy.isValidToken(token)
                )
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Telegram Chat ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = chatId.isNotEmpty() && !SecurityPolicy.isValidChatId(chatId)
                )
                OutlinedTextField(
                    value = webServerPassword,
                    onValueChange = { webServerPassword = it.take(64) },
                    label = { Text("Password Web Server") },
                    supportingText = {
                        Text("Password untuk login ke web server file drive (minimal 4 karakter).")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = webServerPassword.isNotEmpty() && !SecurityPolicy.isValidWebServerPassword(webServerPassword)
                )
                Text(
                    "Restart bot service after saving to apply new settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validCredentials && validPassword,
                onClick = { onSave(token.trim(), chatId.trim(), webServerPassword.trim()) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


@Composable
fun CronJobList(cronJobs: List<CronJob>, onRemove: (String) -> Unit) {
    if (cronJobs.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No cronjobs scheduled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cronJobs.forEach { job ->
                CronJobCard(job, onRemove)
            }
        }
    }
}

@Composable
fun CronJobCard(job: CronJob, onRemove: (String) -> Unit) {
    val currentLocale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = job.command,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Every day at ${String.format(currentLocale, "%02d:%02d", job.hour, job.minute)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = { onRemove(job.id) }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}


@Composable
fun StatusCard(stats: BotStats) {
    val currentLocale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                when (stats.status) {
                                    "Connected" -> Color(0xFF2E7D32)
                                    "Disabled", "Stopped" -> Color.Gray
                                    else -> Color(0xFFD32F2F)
                                },
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stats.status,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = when (stats.status) {
                            "Connected" -> Color(0xFF2E7D32)
                            "Disabled", "Stopped" -> Color.Gray
                            else -> Color(0xFFD32F2F)
                        }
                    )
                }
                
                stats.lastUpdate?.let {
                    val sdf = SimpleDateFormat("HH:mm:ss", currentLocale)
                    Text(
                        text = "Last: ${sdf.format(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Contacts", stats.contactsCount.toString())
                StatItem("Photos", stats.photosTaken.toString())
                StatItem("Errors", stats.errorCount.toString(), isError = stats.errorCount > 0)
            }
            
            if (stats.lastError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Last Error: ${stats.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, isError: Boolean = false) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = if (isError) Color(0xFFD32F2F) else Color(0xFF1C1B1F)
        )
    }
}

@Composable
fun ReminderList(reminders: List<ReminderItem>) {
    val pending = reminders.filter { !it.isDone }
    
    if (pending.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "No pending reminders", color = Color.Gray)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pending.forEach { reminder ->
                ReminderCard(reminder)
            }
        }
    }
}

@Composable
fun ReminderCard(reminder: ReminderItem) {
    val currentLocale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF4A3C8C), CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = reminder.message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
                val sdf = SimpleDateFormat("HH:mm", currentLocale)
                Text(
                    text = "Due at ${sdf.format(Date(reminder.targetTime))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun EventCalendarCard(
    events: List<CalendarEventItem>,
    onAddEvent: () -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📅 Event Calendar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFE3F2FD),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${events.count { !it.isNotified }} aktif",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1565C0)
                        )
                    }
                }
                IconButton(onClick = onAddEvent) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah Event", tint = Color(0xFF1565C0))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Belum ada event terjadwal", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    events.forEach { event ->
                        val isPast = event.eventTime < now
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (event.isNotified) Color(0xFFF5F5F5) else if (isPast) Color(0xFFFFF3E0) else Color(0xFFF1F8E9),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (event.isNotified) Color(0xFFE0E0E0) else if (isPast) Color(0xFFFFB74D) else Color(0xFFC5E1A5))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        event.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "⏰ ${sdf.format(Date(event.eventTime))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isPast && !event.isNotified) Color(0xFFD84315) else Color(0xFF558B2F)
                                    )
                                    if (event.description.isNotBlank()) {
                                        Text(
                                            "📝 ${event.description}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.DarkGray
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteEvent(event.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, epochMs: Long) -> Unit
) {
    val now = remember { Calendar.getInstance() }
    val defaultDateStr = remember {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sdf.format(now.time)
    }
    val defaultTimeStr = remember {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(now.time)
    }

    var dateStr by remember { mutableStateOf(defaultDateStr) }
    var timeStr by remember { mutableStateOf(defaultTimeStr) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    val parsedEpoch = remember(dateStr, timeStr) {
        val full = "$dateStr $timeStr"
        val patterns = listOf("yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm")
        var res = -1L
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.getDefault()).apply { isLenient = false }
                val d = sdf.parse(full)
                if (d != null) { res = d.time; break }
            } catch (_: Exception) {}
        }
        res
    }

    val isValid = title.isNotBlank() && parsedEpoch > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Event Kalender") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Judul Event") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Catatan / Deskripsi (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("Tanggal (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Jam (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (parsedEpoch <= 0L) {
                    Text("Format tanggal atau jam tidak valid", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onSave(title.trim(), description.trim(), parsedEpoch) }
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@Composable
fun ClassScheduleCard(
    schedules: List<ClassScheduleItem>,
    onAddSchedule: () -> Unit,
    onDeleteSchedule: (String) -> Unit
) {
    val dayNames = listOf("Semua", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    var selectedDayIndex by remember { mutableStateOf(0) }

    val filtered = if (selectedDayIndex == 0) {
        schedules
    } else {
        schedules.filter { it.dayOfWeek == selectedDayIndex }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎓 Jadwal Kuliah", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFFEDE7F6),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${schedules.size} matkul",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF512DA8)
                        )
                    }
                }
                IconButton(onClick = onAddSchedule) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah Jadwal", tint = Color(0xFF512DA8))
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                dayNames.forEachIndexed { index, name ->
                    val isSelected = selectedDayIndex == index
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedDayIndex = index },
                        label = { Text(name, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (selectedDayIndex == 0) "Belum ada jadwal kuliah yang diatur" else "Tidak ada jadwal kuliah di hari ${dayNames[selectedDayIndex]}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filtered.forEach { s ->
                        val dayLabel = dayNames.getOrElse(s.dayOfWeek) { "Hari ${s.dayOfWeek}" }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF9F7FF),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFD1C4E9))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = Color(0xFF673AB7),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = dayLabel,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "⏰ ${s.startTime} - ${s.endTime}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF4527A0)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        s.subject,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "📍 Ruang: ${s.room}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                    if (s.lecturer.isNotBlank()) {
                                        Text(
                                            "👨‍🏫 Dosen: ${s.lecturer}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteSchedule(s.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddClassScheduleDialog(
    onDismiss: () -> Unit,
    onSave: (dayOfWeek: Int, start: String, end: String, subject: String, room: String, lecturer: String) -> Unit
) {
    val dayNames = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    var selectedDay by remember { mutableStateOf(1) }
    var startTime by remember { mutableStateOf("08:00") }
    var endTime by remember { mutableStateOf("10:30") }
    var subject by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var lecturer by remember { mutableStateOf("") }

    val isValid = subject.isNotBlank() && room.isNotBlank() && startTime.isNotBlank() && endTime.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Jadwal Kuliah") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Pilih Hari:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    dayNames.forEachIndexed { idx, name ->
                        val dayNum = idx + 1
                        FilterChip(
                            selected = selectedDay == dayNum,
                            onClick = { selectedDay = dayNum },
                            label = { Text(name, fontSize = 11.sp) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("Mulai (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("Selesai (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Nama Mata Kuliah") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Ruangan / Lab") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = lecturer,
                    onValueChange = { lecturer = it },
                    label = { Text("Dosen Pengampu (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onSave(selectedDay, startTime.trim(), endTime.trim(), subject.trim(), room.trim(), lecturer.trim()) }
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}
