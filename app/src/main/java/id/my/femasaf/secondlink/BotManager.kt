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

    suspend fun getPendingReminders(): List<ReminderEntity> {
        return database?.botDao()?.getPendingReminders() ?: emptyList()
    }
}
