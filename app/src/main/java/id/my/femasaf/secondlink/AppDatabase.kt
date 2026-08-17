package id.my.femasaf.secondlink

import android.content.Context
import androidx.room.*

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val message: String,
    val targetTime: Long,
    val isDone: Boolean = false
)

@Entity(tableName = "cronjobs")
data class CronJobEntity(
    @PrimaryKey val id: String,
    val command: String,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean = true
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val command: String,
    val timestamp: Long,
    val status: String,
    val response: String? = null
)

@Entity(tableName = "calendar_events")
data class EventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val eventTime: Long,
    val isNotified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "class_schedules")
data class ClassScheduleEntity(
    @PrimaryKey val id: String,
    val dayOfWeek: Int, // 1 = Senin, 2 = Selasa, 3 = Rabu, 4 = Kamis, 5 = Jumat, 6 = Sabtu, 7 = Minggu
    val startTime: String, // "08:00"
    val endTime: String, // "10:30"
    val subject: String, // "Mata Kuliah"
    val room: String, // "Ruangan"
    val lecturer: String = "" // Dosen pengampu (opsional)
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val yamlContent: String,
    val isEnabled: Boolean = true,
    val lastRunTime: Long = 0L,
    val lastStatus: String = "Belum pernah dijalankan",
    val triggerType: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface BotDao {
    @Query("SELECT * FROM reminders")
    suspend fun getAllReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE isDone = 0")
    suspend fun getPendingReminders(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Query("SELECT * FROM cronjobs")
    fun getAllCronJobs(): kotlinx.coroutines.flow.Flow<List<CronJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCronJob(cronJob: CronJobEntity)

    @Query("DELETE FROM cronjobs WHERE id = :id")
    suspend fun deleteCronJob(id: String)

    @Query("SELECT * FROM calendar_events ORDER BY eventTime ASC")
    fun getAllEvents(): kotlinx.coroutines.flow.Flow<List<EventEntity>>

    @Query("SELECT * FROM calendar_events ORDER BY eventTime ASC")
    suspend fun getEventsList(): List<EventEntity>

    @Query("SELECT * FROM calendar_events WHERE isNotified = 0 ORDER BY eventTime ASC")
    suspend fun getPendingEvents(): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    @Update
    suspend fun updateEvent(event: EventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("UPDATE calendar_events SET isNotified = 1 WHERE id = :id")
    suspend fun markEventNotified(id: String)

    @Query("SELECT * FROM class_schedules ORDER BY dayOfWeek ASC, startTime ASC")
    fun getAllClassSchedules(): kotlinx.coroutines.flow.Flow<List<ClassScheduleEntity>>

    @Query("SELECT * FROM class_schedules ORDER BY dayOfWeek ASC, startTime ASC")
    suspend fun getClassSchedulesList(): List<ClassScheduleEntity>

    @Query("SELECT * FROM class_schedules WHERE dayOfWeek = :dayOfWeek ORDER BY startTime ASC")
    suspend fun getClassSchedulesByDay(dayOfWeek: Int): List<ClassScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClassSchedule(schedule: ClassScheduleEntity)

    @Update
    suspend fun updateClassSchedule(schedule: ClassScheduleEntity)

    @Query("DELETE FROM class_schedules WHERE id = :id")
    suspend fun deleteClassSchedule(id: String)

    @Query("SELECT * FROM plugins ORDER BY updatedAt DESC")
    fun getAllPlugins(): kotlinx.coroutines.flow.Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins ORDER BY updatedAt DESC")
    suspend fun getPluginsList(): List<PluginEntity>

    @Query("SELECT * FROM plugins WHERE id = :id LIMIT 1")
    suspend fun getPluginById(id: String): PluginEntity?

    @Query("SELECT * FROM plugins WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getPluginByName(name: String): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugin(plugin: PluginEntity)

    @Update
    suspend fun updatePlugin(plugin: PluginEntity)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun deletePlugin(id: String)

    @Insert
    suspend fun insertAuditLog(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<AuditLogEntity>

    @Query("DELETE FROM audit_logs WHERE id NOT IN (SELECT id FROM audit_logs ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimAuditLogs(keep: Int)

    @Query("DELETE FROM audit_logs WHERE timestamp < :cutoff")
    suspend fun deleteAuditLogsOlderThan(cutoff: Long)
}

@Database(entities = [ReminderEntity::class, CronJobEntity::class, AuditLogEntity::class, EventEntity::class, ClassScheduleEntity::class, PluginEntity::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    abstract fun botDao(): BotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bot_database"
                )
                .fallbackToDestructiveMigration() // Version changed, simple reset for now
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
