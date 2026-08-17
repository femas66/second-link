package id.my.femasaf.secondlink

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

data class BotStats(
    val status: String = "Inactive",
    val lastUpdate: Date? = null,
    val contactsCount: Int = 0,
    val photosTaken: Int = 0,
    val errorCount: Int = 0,
    val lastError: String? = null
)

data class ReminderItem(
    val id: String,
    val message: String,
    val targetTime: Long,
    val isDone: Boolean = false
)

data class CalendarEventItem(
    val id: String,
    val title: String,
    val description: String = "",
    val eventTime: Long,
    val isNotified: Boolean = false
)

data class ClassScheduleItem(
    val id: String,
    val dayOfWeek: Int, // 1 = Senin .. 7 = Minggu
    val startTime: String,
    val endTime: String,
    val subject: String,
    val room: String,
    val lecturer: String = ""
)

data class PluginItem(
    val id: String,
    val name: String,
    val description: String,
    val yamlContent: String,
    val isEnabled: Boolean,
    val lastRunTime: Long,
    val lastStatus: String,
    val triggerType: String,
    val updatedAt: Long
)

data class AuditLogItem(
    val command: String,
    val timestamp: Long,
    val status: String
)

data class DashboardUiState(
    val stats: BotStats = BotStats(),
    val reminders: List<ReminderItem> = emptyList(),
    val events: List<CalendarEventItem> = emptyList(),
    val classSchedules: List<ClassScheduleItem> = emptyList(),
    val plugins: List<PluginItem> = emptyList(),
    val cronJobs: List<CronJob> = emptyList(),
    val logs: List<AuditLogItem> = emptyList(),
    val botToken: String = "",
    val chatId: String = "",
    val deviceHealth: DeviceHealthLoadState = DeviceHealthLoadState()
)

class BotViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.botDao()
    private val deviceHealthSource = AndroidDeviceHealthSource(application)
    private val deviceHealthMutex = Mutex()
    private var deviceHealthMonitoringJob: Job? = null

    init {
        BotManager.viewModel = this
        val token = Config.getToken(application)
        val cid = Config.getChatId(application)
        _uiState.update { it.copy(botToken = token, chatId = cid) }
        loadData()
        refreshDeviceHealth()
    }

    private fun loadData() {
        viewModelScope.launch {
            val reminders = dao.getAllReminders().map {
                ReminderItem(it.id, it.message, it.targetTime, it.isDone)
            }
            val cronJobs = dao.getAllCronJobs().first().map {
                CronJob(it.id, it.command, it.hour, it.minute, it.isActive)
            }
            val logs = dao.getRecentLogs().map {
                AuditLogItem(it.command, it.timestamp, it.status)
            }
            _uiState.update { it.copy(reminders = reminders, cronJobs = cronJobs, logs = logs) }
        }
        viewModelScope.launch {
            dao.getAllEvents().collect { entities ->
                val events = entities.map {
                    CalendarEventItem(it.id, it.title, it.description, it.eventTime, it.isNotified)
                }
                _uiState.update { it.copy(events = events) }
            }
        }
        viewModelScope.launch {
            dao.getAllClassSchedules().collect { entities ->
                val schedules = entities.map {
                    ClassScheduleItem(
                        it.id,
                        it.dayOfWeek,
                        it.startTime,
                        it.endTime,
                        it.subject,
                        it.room,
                        it.lecturer
                    )
                }
                _uiState.update { it.copy(classSchedules = schedules) }
            }
        }
        viewModelScope.launch {
            dao.getAllPlugins().collect { entities ->
                val plugins = entities.map {
                    PluginItem(
                        it.id,
                        it.name,
                        it.description,
                        it.yamlContent,
                        it.isEnabled,
                        it.lastRunTime,
                        it.lastStatus,
                        it.triggerType,
                        it.updatedAt
                    )
                }
                _uiState.update { it.copy(plugins = plugins) }
            }
        }
    }

    fun updateConfig(token: String, chatId: String, webServerPassword: String): Boolean {
        val oldToken = Config.getToken(getApplication())
        if (!Config.saveConfig(getApplication(), token, chatId)) return false
        if (webServerPassword.isNotEmpty() && !Config.setWebServerPassword(getApplication(), webServerPassword)) return false
        _uiState.update { it.copy(botToken = token, chatId = chatId) }
        
        // Restart service if token changed
        if (oldToken != token) {
            val context = getApplication<Application>()
            val intent = Intent(context, BotService::class.java).apply {
                action = "RESTART_POLLING"
            }
            context.startService(intent)
        }
        return true
    }

    fun updateStats(status: String? = null, error: String? = null, contactAdded: Boolean = false, photoAdded: Boolean = false) {
        _uiState.update { currentState ->
            currentState.copy(
                stats = currentState.stats.copy(
                    status = status ?: currentState.stats.status,
                    lastUpdate = Date(),
                    contactsCount = if (contactAdded) currentState.stats.contactsCount + 1 else currentState.stats.contactsCount,
                    photosTaken = if (photoAdded) currentState.stats.photosTaken + 1 else currentState.stats.photosTaken,
                    errorCount = if (error != null) currentState.stats.errorCount + 1 else currentState.stats.errorCount,
                    lastError = error ?: currentState.stats.lastError
                )
            )
        }
    }

    fun syncCronJobs(jobs: List<CronJob>) {
        _uiState.update { it.copy(cronJobs = jobs) }
    }

    fun addReminderWithTime(id: String, message: String, targetTime: Long) {
        val reminder = ReminderItem(id = id, message = message, targetTime = targetTime)
        _uiState.update { it.copy(reminders = it.reminders + reminder) }
        
        viewModelScope.launch {
            dao.insertReminder(ReminderEntity(id, message, targetTime))
        }
    }

    fun completeReminder(id: String) {
        _uiState.update { currentState ->
            currentState.copy(
                reminders = currentState.reminders.map {
                    if (it.id == id) it.copy(isDone = true) else it
                }
            )
        }
        viewModelScope.launch {
            val item = _uiState.value.reminders.find { it.id == id }
            if (item != null) {
                dao.updateReminder(ReminderEntity(item.id, item.message, item.targetTime, true))
            }
        }
    }

    fun addCronJob(command: String, hour: Int, minute: Int) {
        BotManager.addCronJob(command, hour, minute)
    }

    fun removeCronJob(id: String) {
        BotManager.removeCronJob(id)
    }

    fun addEvent(title: String, description: String, eventTime: Long) {
        val id = java.util.UUID.randomUUID().toString()
        BotManager.addEvent(id, title, description, eventTime)
    }

    fun deleteEvent(id: String) {
        BotManager.deleteEvent(id)
    }

    fun addClassSchedule(dayOfWeek: Int, startTime: String, endTime: String, subject: String, room: String, lecturer: String = "") {
        val id = java.util.UUID.randomUUID().toString()
        BotManager.addClassSchedule(id, dayOfWeek, startTime, endTime, subject, room, lecturer)
    }

    fun deleteClassSchedule(id: String) {
        BotManager.deleteClassSchedule(id)
    }

    fun savePlugin(id: String?, yamlContent: String): Result<String> {
        val parseResult = PluginParser.parse(yamlContent)
        if (parseResult.isFailure) {
            return Result.failure(parseResult.exceptionOrNull() ?: IllegalArgumentException("Invalid YAML"))
        }
        val parsed = parseResult.getOrThrow()
        val pluginId = id ?: java.util.UUID.randomUUID().toString()
        BotManager.savePlugin(pluginId, parsed.name, parsed.description, yamlContent, isEnabled = true)
        return Result.success(pluginId)
    }

    fun togglePlugin(id: String, isEnabled: Boolean) {
        BotManager.togglePlugin(id, isEnabled)
    }

    fun deletePlugin(id: String) {
        BotManager.deletePlugin(id)
    }

    fun runPlugin(id: String) {
        viewModelScope.launch {
            val entity = BotManager.getPluginById(id) ?: return@launch
            val parsed = PluginParser.parse(entity.yamlContent).getOrNull() ?: return@launch
            BotManager.pluginEngine?.executePlugin(entity.id, parsed, forceRun = true)
        }
    }

    fun refreshDeviceHealth() {
        viewModelScope.launch { refreshDeviceHealthNow() }
    }

    fun startDeviceHealthMonitoring() {
        if (deviceHealthMonitoringJob?.isActive == true) return
        deviceHealthMonitoringJob = viewModelScope.launch {
            refreshDeviceHealthNow()
            while (isActive) {
                delay(DEVICE_HEALTH_REFRESH_INTERVAL_MS)
                refreshDeviceHealthNow()
            }
        }
    }

    fun stopDeviceHealthMonitoring() {
        deviceHealthMonitoringJob?.cancel()
        deviceHealthMonitoringJob = null
    }

    private suspend fun refreshDeviceHealthNow() {
        deviceHealthMutex.withLock {
            _uiState.update { state ->
                state.copy(deviceHealth = DeviceHealthLoadReducer.started(state.deviceHealth))
            }
            try {
                val input = deviceHealthSource.read(_uiState.value.stats.status)
                val snapshot = DeviceHealthEvaluator.evaluate(input, System.currentTimeMillis())
                _uiState.update { state ->
                    state.copy(
                        deviceHealth = DeviceHealthLoadReducer.succeeded(
                            state.deviceHealth,
                            snapshot
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    state.copy(deviceHealth = DeviceHealthLoadReducer.failed(state.deviceHealth))
                }
            }
        }
    }

    override fun onCleared() {
        stopDeviceHealthMonitoring()
        if (BotManager.viewModel === this) {
            BotManager.viewModel = null
        }
    }

    private companion object {
        const val DEVICE_HEALTH_REFRESH_INTERVAL_MS = 30_000L
    }
}
