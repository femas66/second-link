package id.my.femasaf.secondlink

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object BotManager {
    var viewModel: BotViewModel? = null
    private var database: AppDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _cronJobs = MutableStateFlow<List<CronJob>>(emptyList())
    val cronJobs: StateFlow<List<CronJob>> = _cronJobs.asStateFlow()

    fun init(context: Context) {
        if (database == null) {
            database = AppDatabase.getDatabase(context)
            loadCronJobs()
        }
    }

    private fun loadCronJobs() {
        scope.launch {
            database?.botDao()?.getAllCronJobs()?.collect { entities ->
                val jobs = entities.map {
                    CronJob(it.id, it.command, it.hour, it.minute, it.isActive)
                }
                _cronJobs.value = jobs
                viewModel?.syncCronJobs(jobs)
            }
        }
    }

    fun logError(error: String) {
        viewModel?.updateStats(error = error)
    }

    fun setStatus(status: String) {
        viewModel?.updateStats(status = status)
    }

    fun recordContact() {
        viewModel?.updateStats(contactAdded = true)
    }

    fun recordPhoto() {
        viewModel?.updateStats(photoAdded = true)
    }

    fun addReminder(id: String, message: String, targetTime: Long) {
        viewModel?.addReminderWithTime(id, message, targetTime)
    }

    fun markReminderDone(id: String) {
        viewModel?.completeReminder(id)
    }

    fun addCronJob(command: String, hour: Int, minute: Int) {
        val job = CronJob(command = command, hour = hour, minute = minute)
        _cronJobs.update { it + job }
        viewModel?.syncCronJobs(_cronJobs.value)
        
        scope.launch {
            database?.botDao()?.insertCronJob(CronJobEntity(job.id, command, hour, minute, true))
        }
    }

    fun removeCronJob(id: String) {
        _cronJobs.update { it.filter { job -> job.id != id } }
        viewModel?.syncCronJobs(_cronJobs.value)
        
        scope.launch {
            database?.botDao()?.deleteCronJob(id)
        }
    }

    fun audit(chatId: String, command: String, status: String, response: String? = null) {
        scope.launch {
            val dao = database?.botDao() ?: return@launch
            val actor = java.security.MessageDigest.getInstance("SHA-256")
                .digest(chatId.toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
            dao.insertAuditLog(
                AuditLogEntity(
                    chatId = actor,
                    command = SecurityPolicy.redactCommand(command),
                    timestamp = System.currentTimeMillis(),
                    status = status,
                    response = null
                )
            )
            dao.deleteAuditLogsOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            dao.trimAuditLogs(500)
        }
    }

    var onEventScheduled: ((EventEntity) -> Unit)? = null

    fun addEvent(id: String, title: String, description: String, eventTime: Long) {
        val entity = EventEntity(id = id, title = title, description = description, eventTime = eventTime)
        scope.launch {
            database?.botDao()?.insertEvent(entity)
            onEventScheduled?.invoke(entity)
        }
    }

    fun deleteEvent(id: String) {
        scope.launch {
            database?.botDao()?.deleteEvent(id)
        }
    }

    fun markEventNotified(id: String) {
        scope.launch {
            database?.botDao()?.markEventNotified(id)
        }
    }

    suspend fun getPendingEvents(): List<EventEntity> {
        return database?.botDao()?.getPendingEvents() ?: emptyList()
    }

    suspend fun getEventsList(): List<EventEntity> {
        return database?.botDao()?.getEventsList() ?: emptyList()
    }

    fun addClassSchedule(id: String, dayOfWeek: Int, startTime: String, endTime: String, subject: String, room: String, lecturer: String = "") {
        val entity = ClassScheduleEntity(id, dayOfWeek, startTime, endTime, subject, room, lecturer)
        scope.launch {
            database?.botDao()?.insertClassSchedule(entity)
        }
    }

    fun deleteClassSchedule(id: String) {
        scope.launch {
            database?.botDao()?.deleteClassSchedule(id)
        }
    }

    suspend fun getClassSchedulesList(): List<ClassScheduleEntity> {
        return database?.botDao()?.getClassSchedulesList() ?: emptyList()
    }

    suspend fun getClassSchedulesByDay(dayOfWeek: Int): List<ClassScheduleEntity> {
        return database?.botDao()?.getClassSchedulesByDay(dayOfWeek) ?: emptyList()
    }

    suspend fun getPendingReminders(): List<ReminderEntity> {
        return database?.botDao()?.getPendingReminders() ?: emptyList()
    }

    var pluginEngine: PluginEngine? = null

    fun savePlugin(id: String, name: String, description: String, yamlContent: String, isEnabled: Boolean = true) {
        val parsed = PluginParser.parse(yamlContent).getOrNull()
        val triggerType = when (parsed?.trigger) {
            is PluginTrigger.Interval -> "every"
            is PluginTrigger.WifiConnected -> "wifi"
            is PluginTrigger.BatteryChanged -> "battery"
            is PluginTrigger.Schedule -> "schedule"
            is PluginTrigger.TelegramCommand -> "command"
            else -> "manual"
        }
        val entity = PluginEntity(
            id = id,
            name = name,
            description = description,
            yamlContent = yamlContent,
            isEnabled = isEnabled,
            triggerType = triggerType,
            updatedAt = System.currentTimeMillis()
        )
        scope.launch {
            database?.botDao()?.insertPlugin(entity)
            pluginEngine?.onPluginUpdatedOrCreated(entity)
        }
    }

    fun togglePlugin(id: String, isEnabled: Boolean) {
        scope.launch {
            val entity = database?.botDao()?.getPluginById(id) ?: return@launch
            val updated = entity.copy(isEnabled = isEnabled, updatedAt = System.currentTimeMillis())
            database?.botDao()?.updatePlugin(updated)
            pluginEngine?.onPluginUpdatedOrCreated(updated)
        }
    }

    fun deletePlugin(id: String) {
        scope.launch {
            database?.botDao()?.deletePlugin(id)
            pluginEngine?.onPluginDeleted(id)
        }
    }

    fun updatePluginStatus(id: String, lastRunTime: Long, lastStatus: String) {
        scope.launch {
            val entity = database?.botDao()?.getPluginById(id) ?: return@launch
            val updated = entity.copy(lastRunTime = lastRunTime, lastStatus = lastStatus)
            database?.botDao()?.updatePlugin(updated)
        }
    }

    suspend fun getPluginsList(): List<PluginEntity> {
        return database?.botDao()?.getPluginsList() ?: emptyList()
    }

    suspend fun getPluginById(id: String): PluginEntity? {
        return database?.botDao()?.getPluginById(id)
    }

    suspend fun getPluginByName(name: String): PluginEntity? {
        return database?.botDao()?.getPluginByName(name)
    }
}
