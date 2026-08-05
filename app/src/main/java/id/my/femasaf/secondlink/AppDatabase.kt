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

    @Insert
    suspend fun insertAuditLog(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<AuditLogEntity>

    @Query("DELETE FROM audit_logs WHERE id NOT IN (SELECT id FROM audit_logs ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimAuditLogs(keep: Int)

    @Query("DELETE FROM audit_logs WHERE timestamp < :cutoff")
    suspend fun deleteAuditLogsOlderThan(cutoff: Long)
}

@Database(entities = [ReminderEntity::class, CronJobEntity::class, AuditLogEntity::class], version = 2)
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
