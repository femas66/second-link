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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import id.my.femasaf.secondlink.ui.theme.status
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var showPluginEditor by remember { mutableStateOf(false) }
    var editingPlugin by remember { mutableStateOf<PluginItem?>(null) }
    var showPluginTemplates by remember { mutableStateOf(false) }
    var initialYamlContent by remember { mutableStateOf("") }
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
        containerColor = MaterialTheme.colorScheme.background,
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

            PluginManagerCard(
                plugins = uiState.plugins,
                onAddPlugin = {
                    editingPlugin = null
                    initialYamlContent = ""
                    showPluginEditor = true
                },
                onOpenTemplates = {
                    showPluginTemplates = true
                },
                onTogglePlugin = { id, enabled -> viewModel.togglePlugin(id, enabled) },
                onRunPlugin = { id -> viewModel.runPlugin(id) },
                onEditPlugin = { plugin ->
                    editingPlugin = plugin
                    initialYamlContent = plugin.yamlContent
                    showPluginEditor = true
                },
                onDeletePlugin = { id -> viewModel.deletePlugin(id) }
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            ReminderList(uiState.reminders)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "CronJobs (Scheduled)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            CronJobList(uiState.cronJobs) { viewModel.removeCronJob(it) }

            Spacer(modifier = Modifier.height(32.dp))

            if (isEnabled) {
                Button(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Emergency Stop (Kill Bot)", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.status.success),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.status.onSuccess)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Bot Service", fontWeight = FontWeight.Bold, color = MaterialTheme.status.onSuccess)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onExitApp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Exit & Close App", fontWeight = FontWeight.Bold)
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

    if (showPluginEditor) {
        PluginEditorDialog(
            initialPlugin = editingPlugin,
            initialYaml = initialYamlContent,
            onDismiss = { showPluginEditor = false; editingPlugin = null },
            onSave = { id, yaml ->
                viewModel.savePlugin(id, yaml)
                showPluginEditor = false
                editingPlugin = null
            }
        )
    }

    if (showPluginTemplates) {
        PluginTemplatesDialog(
            onDismiss = { showPluginTemplates = false },
            onSelectTemplate = { template ->
                editingPlugin = null
                initialYamlContent = template.yamlContent
                showPluginTemplates = false
                showPluginEditor = true
            }
        )
    }
}

@Composable
fun MediaAccessCard() {
    val context = LocalContext.current
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
    val status = MaterialTheme.status
    val containerColor = if (enabled) status.successContainer else status.warningContainer
    val onContainerColor = if (enabled) status.onSuccessContainer else status.onWarningContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (enabled) "Kontrol media siap" else "Akses kontrol media belum aktif",
                fontWeight = FontWeight.Bold,
                color = onContainerColor
            )
            Text(
                if (enabled) "Telegram dapat mengontrol sesi media aktif di HP ini."
                else "Aktifkan Notification access agar Spotify, YouTube Music, dan pemutar lain dapat dikontrol.",
                style = MaterialTheme.typography.bodySmall,
                color = onContainerColor
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = onContainerColor),
                border = BorderStroke(1.dp, onContainerColor.copy(alpha = 0.4f))
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
    val serverActive = serviceEnabled && webServerEnabled
    val status = MaterialTheme.status

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SecondLink Drive (Web Server)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = if (serverActive) status.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (serverActive) "Server Aktif" else "Server Nonaktif",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (serverActive) status.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            if (serviceEnabled && webServerEnabled && address != null) {
                Text(
                    "$address\nUsername: admin · Password: $password",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    when {
                        !serviceEnabled -> "Layanan bot tidak aktif. Jalankan bot service untuk mengaktifkan server."
                        !webServerEnabled -> "Web server dimatikan. Aktifkan saklar di atas atau kirim /web on via bot Telegram."
                        else -> "Hubungkan perangkat ke Wi-Fi untuk mendapatkan alamat server lokal."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No cronjobs scheduled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Every day at ${String.format(currentLocale, "%02d:%02d", job.hour, job.minute)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onRemove(job.id) }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}


@Composable
fun StatusCard(stats: BotStats) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val status = MaterialTheme.status
    val statusColor = when (stats.status) {
        "Connected" -> status.success
        "Disabled", "Stopped" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stats.status,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                }

                stats.lastUpdate?.let {
                    val sdf = SimpleDateFormat("HH:mm:ss", currentLocale)
                    Text(
                        text = "Last: ${sdf.format(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.extraSmall)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, isError: Boolean = false) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
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
            Text(text = "No pending reminders", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = reminder.message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val sdf = SimpleDateFormat("HH:mm", currentLocale)
                Text(
                    text = "Due at ${sdf.format(Date(reminder.targetTime))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val status = MaterialTheme.status

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("Event Calendar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${events.count { !it.isNotified }} aktif",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                IconButton(onClick = onAddEvent) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah Event", tint = MaterialTheme.colorScheme.primary)
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
                    Text("Belum ada event terjadwal", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    events.forEach { event ->
                        val isPast = event.eventTime < now
                        val (surfaceColor, borderColor, accentColor) = when {
                            event.isNotified -> Triple(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                            isPast -> Triple(status.warningContainer, status.warningContainer, status.onWarningContainer)
                            else -> Triple(status.successContainer, status.successContainer, status.onSuccessContainer)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = surfaceColor,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, borderColor)
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
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = accentColor
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = accentColor)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            sdf.format(Date(event.eventTime)),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accentColor
                                        )
                                    }
                                    if (event.description.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp).padding(top = 2.dp), tint = accentColor)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                event.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = accentColor
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { onDeleteEvent(event.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = accentColor)
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
                    Text("Format tanggal atau jam tidak valid", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("Jadwal Kuliah", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${schedules.size} matkul",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                IconButton(onClick = onAddSchedule) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah Jadwal", tint = MaterialTheme.colorScheme.primary)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filtered.forEach { s ->
                        val dayLabel = dayNames.getOrElse(s.dayOfWeek) { "Hari ${s.dayOfWeek}" }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = dayLabel,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "${s.startTime} - ${s.endTime}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        s.subject,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Ruang: ${s.room}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (s.lecturer.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "Dosen: ${s.lecturer}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { onDeleteSchedule(s.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun PluginManagerCard(
    plugins: List<PluginItem>,
    onAddPlugin: () -> Unit,
    onOpenTemplates: () -> Unit,
    onTogglePlugin: (id: String, enabled: Boolean) -> Unit,
    onRunPlugin: (id: String) -> Unit,
    onEditPlugin: (plugin: PluginItem) -> Unit,
    onDeletePlugin: (id: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("Dynamic Plugin YAML", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${plugins.count { it.isEnabled }}/${plugins.size} aktif",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Row {
                    IconButton(onClick = onOpenTemplates) {
                        Icon(Icons.Default.Lightbulb, contentDescription = "Rekomendasi Plugin", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onAddPlugin) {
                        Icon(Icons.Default.Add, contentDescription = "Tambah Plugin", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                text = "Otomatisasi kustom berbasis YAML tanpa perlu update aplikasi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (plugins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Belum ada plugin YAML terpasang.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onOpenTemplates,
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pilih dari Rekomendasi Template", fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plugins.forEach { plugin ->
                        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                        val lastRunStr = if (plugin.lastRunTime > 0L) sdf.format(Date(plugin.lastRunTime)) else "Belum pernah"

                        val status = MaterialTheme.status
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (plugin.isEnabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = plugin.triggerType.uppercase(),
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            plugin.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Switch(
                                        checked = plugin.isEnabled,
                                        onCheckedChange = { onTogglePlugin(plugin.id, it) }
                                    )
                                }

                                if (plugin.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "$lastRunStr · Status: ${plugin.lastStatus}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { onRunPlugin(plugin.id) },
                                        enabled = plugin.isEnabled,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (plugin.isEnabled) status.success else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Jalankan", fontSize = 12.sp, color = if (plugin.isEnabled) status.success else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(
                                        onClick = { onEditPlugin(plugin) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Edit", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(
                                        onClick = { onDeletePlugin(plugin.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
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
fun PluginEditorDialog(
    initialPlugin: PluginItem?,
    initialYaml: String,
    onDismiss: () -> Unit,
    onSave: (id: String?, yamlContent: String) -> Unit
) {
    var yamlText by remember {
        mutableStateOf(
            if (initialPlugin != null) initialPlugin.yamlContent
            else if (initialYaml.isNotBlank()) initialYaml
            else """
name: my-plugin
description: "Deskripsi otomatisasi kustom"

trigger:
  every: 5m

conditions:
  - battery_above: 20

actions:
  - telegram:
      message: "Status OK pada {{time}}"
            """.trimIndent()
        )
    }

    var parseError by remember { mutableStateOf<String?>(null) }
    var parseSuccess by remember { mutableStateOf<String?>(null) }

    fun validateYaml() {
        val result = PluginParser.parse(yamlText)
        if (result.isSuccess) {
            val p = result.getOrThrow()
            parseError = null
            parseSuccess = "Valid: '${p.name}' (${p.actions.size} aksi terdaftar)"
        } else {
            parseSuccess = null
            parseError = result.exceptionOrNull()?.message ?: "Format YAML tidak valid"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPlugin != null) "Edit Plugin: ${initialPlugin.name}" else "Tambah Plugin YAML Baru") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Tulis atau tempel kode konfigurasi plugin YAML di bawah:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = yamlText,
                    onValueChange = {
                        yamlText = it
                        parseError = null
                        parseSuccess = null
                    },
                    label = { Text("Konfigurasi YAML") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )

                if (parseError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = parseError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (parseSuccess != null) {
                    Surface(
                        color = MaterialTheme.status.successContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = parseSuccess!!,
                            color = MaterialTheme.status.onSuccessContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = { validateYaml() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cek Validitas YAML", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = PluginParser.parse(yamlText)
                    if (result.isSuccess) {
                        onSave(initialPlugin?.id, yamlText.trim())
                    } else {
                        parseError = result.exceptionOrNull()?.message ?: "Format YAML tidak valid"
                    }
                }
            ) {
                Text("Simpan Plugin")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginTemplatesDialog(
    onDismiss: () -> Unit,
    onSelectTemplate: (PluginTemplate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rekomendasi Template Plugin")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pilih salah satu template siap pakai di bawah untuk langsung memuat dan mengkustomisasinya:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                PluginCatalog.templates.forEach { template ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(template.icon, fontSize = 20.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(template.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                        Text(template.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Button(
                                    onClick = { onSelectTemplate(template) },
                                    shape = MaterialTheme.shapes.extraSmall,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Pakai", fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}
