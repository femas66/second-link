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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import id.my.femasaf.secondlink.ui.theme.SecondLinkTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {

    private val viewModel: BotViewModel by viewModels()
    private val isAuthenticated = mutableStateOf(false)

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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        
        BotManager.init(this)
        BotManager.viewModel = viewModel
        
        setContent {
            SecondLinkTheme {
                if (isAuthenticated.value) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onStopService = { stopBotService() },
                        onStartService = { startBotService() },
                        onExitApp = { exitFullApp() }
                    )
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Button(onClick = ::authenticateOwner) {
                                Text("Buka dengan kunci perangkat")
                            }
                        }
                    }
                }
            }
        }

        authenticateOwner()
        checkAndRequestPermissions()
    }

    override fun onStop() {
        super.onStop()
        isAuthenticated.value = false
    }

    private fun authenticateOwner() {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAuthenticated.value = true
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verifikasi pemilik perangkat")
                .setSubtitle("Autentikasi diperlukan untuk membuka dashboard")
                .setAllowedAuthenticators(allowed)
                .build()
        )
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
        
        if (Config.isBotEnabled(this) && Config.hasValidCredentials(this) && Config.hasAccessCode(this)) {
            startBotService()
        } else {
            BotManager.setStatus("Disabled")
        }
    }

    private fun startBotService() {
        if (!Config.hasValidCredentials(this) || !Config.hasAccessCode(this)) {
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
        SettingsDialog(
            initialToken = uiState.botToken,
            initialChatId = uiState.chatId,
            hasAccessCode = Config.hasAccessCode(viewModel.getApplication()),
            onDismiss = { showSettings = false },
            onSave = { token, chatId, accessCode ->
                if (viewModel.updateConfig(token, chatId, accessCode)) {
                    showSettings = false
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialToken: String,
    initialChatId: String,
    hasAccessCode: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var chatId by remember { mutableStateOf(initialChatId) }
    var accessCode by remember { mutableStateOf("") }
    val validCredentials = SecurityPolicy.isValidToken(token) && SecurityPolicy.isValidChatId(chatId)
    val validAccessCode = hasAccessCode || SecurityPolicy.isStrongAccessCode(accessCode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bot Configuration") },
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
                    value = accessCode,
                    onValueChange = { accessCode = it.take(64) },
                    label = { Text(if (hasAccessCode) "Kode akses baru (opsional)" else "Kode akses command") },
                    supportingText = {
                        Text("Minimal 8 karakter, huruf dan angka. Tambahkan #kode pada command sensitif.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    isError = accessCode.isNotEmpty() && !SecurityPolicy.isStrongAccessCode(accessCode)
                )
                Text(
                    "Restart bot service after saving to apply new token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validCredentials && validAccessCode,
                onClick = { onSave(token.trim(), chatId.trim(), accessCode) }
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
