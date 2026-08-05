package id.my.femasaf.secondlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceHealthSnapshotTest {

    @Test
    fun primaryIssueIsTheFirstItemAtTheWorstKnownLevel() {
        val snapshot = DeviceHealthEvaluator.evaluate(
            input = healthyInput().copy(
                temperatureC = 46f,
                internetValidated = false,
                cameraGranted = false
            ),
            checkedAtMillis = 2_000L
        )

        assertEquals(HealthLevel.CRITICAL, snapshot.overallLevel)
        assertEquals("battery_temperature", snapshot.primaryIssue?.id)
    }

    @Test
    fun itemsRemainInAStableUserFacingOrder() {
        val ids = DeviceHealthEvaluator.evaluate(healthyInput(), 3_000L).items.map { it.id }

        assertEquals(
            listOf(
                "battery_level",
                "battery_temperature",
                "battery_condition",
                "storage",
                "internet",
                "network_type",
                "secondlink_service",
                "uptime",
                "camera",
                "contacts",
                "notifications",
                "media_access",
                "battery_optimization"
            ),
            ids
        )
    }

    @Test
    fun allUnavailableDataProducesUnavailableWithoutPrimaryIssue() {
        val snapshot = DeviceHealthEvaluator.evaluate(
            DeviceHealthInput(
                batteryPercent = null,
                charging = null,
                temperatureC = null,
                batteryCondition = BatteryCondition.UNKNOWN,
                storageAvailableBytes = null,
                storageTotalBytes = null,
                networkType = null,
                internetValidated = null,
                uptimeMillis = null,
                botStatus = null,
                cameraGranted = null,
                contactsGranted = null,
                notificationsGranted = null,
                mediaAccessGranted = null,
                batteryOptimizationIgnored = null
            ),
            checkedAtMillis = 4_000L
        )

        assertEquals(HealthLevel.UNAVAILABLE, snapshot.overallLevel)
        assertNull(snapshot.primaryIssue)
        assertEquals("Tidak tersedia", snapshot.items.first().value)
    }

    private fun healthyInput() = DeviceHealthInput(
        batteryPercent = 80,
        charging = false,
        temperatureC = 30f,
        batteryCondition = BatteryCondition.GOOD,
        storageAvailableBytes = 20L * 1_073_741_824L,
        storageTotalBytes = 100L * 1_073_741_824L,
        networkType = "Wi-Fi",
        internetValidated = true,
        uptimeMillis = 3_600_000L,
        botStatus = "Connected",
        cameraGranted = true,
        contactsGranted = true,
        notificationsGranted = true,
        mediaAccessGranted = true,
        batteryOptimizationIgnored = true
    )
}
