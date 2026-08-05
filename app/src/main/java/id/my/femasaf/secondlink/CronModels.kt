package id.my.femasaf.secondlink

import java.util.UUID

data class CronJob(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean = true,
    val description: String = ""
)

data class CronUiState(
    val cronJobs: List<CronJob> = emptyList()
)
