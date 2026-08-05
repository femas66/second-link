package id.my.femasaf.secondlink

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHealthEvaluatorTest {

    @Test
    fun temperatureBoundariesUseWarningAt40AndCriticalAt45() {
        assertEquals(HealthLevel.GOOD, evaluate(temperatureC = 39.9f).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(temperatureC = 40f).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(temperatureC = 44.9f).overallLevel)
        assertEquals(HealthLevel.CRITICAL, evaluate(temperatureC = 45f).overallLevel)
    }

    @Test
    fun lowBatteryOnlyWarnsWhenDeviceIsNotCharging() {
        assertEquals(HealthLevel.CRITICAL, evaluate(batteryPercent = 4, charging = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(batteryPercent = 5, charging = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(batteryPercent = 14, charging = false).overallLevel)
        assertEquals(HealthLevel.GOOD, evaluate(batteryPercent = 15, charging = false).overallLevel)
        assertEquals(HealthLevel.GOOD, evaluate(batteryPercent = 4, charging = true).overallLevel)
    }

    @Test
    fun storageBecomesCriticalBelowFivePercentOrOneGibibyte() {
        assertEquals(
            HealthLevel.CRITICAL,
            evaluate(storageAvailableBytes = 1_999_999_999L, storageTotalBytes = 40_000_000_000L).overallLevel
        )
        assertEquals(
            HealthLevel.CRITICAL,
            evaluate(storageAvailableBytes = GIB - 1L, storageTotalBytes = 10L * GIB).overallLevel
        )
        assertEquals(
            HealthLevel.WARNING,
            evaluate(storageAvailableBytes = 2L * GIB, storageTotalBytes = 40L * GIB).overallLevel
        )
        assertEquals(
            HealthLevel.GOOD,
            evaluate(storageAvailableBytes = 4L * GIB, storageTotalBytes = 40L * GIB).overallLevel
        )
    }

    @Test
    fun unavailableInternetIsCriticalButUnknownInternetIsNotAssumedHealthy() {
        assertEquals(HealthLevel.CRITICAL, evaluate(internetValidated = false).overallLevel)

        val unknownInternet = evaluate(internetValidated = null)
        assertEquals(
            HealthLevel.UNAVAILABLE,
            unknownInternet.items.first { it.id == "internet" }.level
        )
    }

    @Test
    fun serviceStatusDistinguishesStoppedFromFailure() {
        assertEquals(HealthLevel.CRITICAL, evaluate(botStatus = "Error").overallLevel)
        assertEquals(HealthLevel.CRITICAL, evaluate(botStatus = "Polling Failed").overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(botStatus = "Stopped").overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(botStatus = "Disabled").overallLevel)
        assertEquals(HealthLevel.GOOD, evaluate(botStatus = "Connected").overallLevel)
    }

    @Test
    fun missingEssentialAccessNeedsAttention() {
        assertEquals(HealthLevel.WARNING, evaluate(cameraGranted = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(contactsGranted = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(notificationsGranted = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(mediaAccessGranted = false).overallLevel)
        assertEquals(HealthLevel.WARNING, evaluate(batteryOptimizationIgnored = false).overallLevel)
    }

    @Test
    fun androidBatteryConditionIsIncludedInOverallHealth() {
        assertEquals(
            HealthLevel.CRITICAL,
            evaluate(batteryCondition = BatteryCondition.DEAD).overallLevel
        )
        assertEquals(
            HealthLevel.CRITICAL,
            evaluate(batteryCondition = BatteryCondition.OVER_VOLTAGE).overallLevel
        )
        assertEquals(
            HealthLevel.CRITICAL,
            evaluate(batteryCondition = BatteryCondition.FAILURE).overallLevel
        )
        assertEquals(
            HealthLevel.WARNING,
            evaluate(batteryCondition = BatteryCondition.COLD).overallLevel
        )
    }

    private fun evaluate(
        batteryPercent: Int? = 80,
        charging: Boolean? = false,
        temperatureC: Float? = 30f,
        batteryCondition: BatteryCondition = BatteryCondition.GOOD,
        storageAvailableBytes: Long? = 20L * GIB,
        storageTotalBytes: Long? = 100L * GIB,
        networkType: String? = "Wi-Fi",
        internetValidated: Boolean? = true,
        uptimeMillis: Long? = 3_600_000L,
        botStatus: String? = "Connected",
        cameraGranted: Boolean? = true,
        contactsGranted: Boolean? = true,
        notificationsGranted: Boolean? = true,
        mediaAccessGranted: Boolean? = true,
        batteryOptimizationIgnored: Boolean? = true
    ): DeviceHealthSnapshot = DeviceHealthEvaluator.evaluate(
        input = DeviceHealthInput(
            batteryPercent = batteryPercent,
            charging = charging,
            temperatureC = temperatureC,
            batteryCondition = batteryCondition,
            storageAvailableBytes = storageAvailableBytes,
            storageTotalBytes = storageTotalBytes,
            networkType = networkType,
            internetValidated = internetValidated,
            uptimeMillis = uptimeMillis,
            botStatus = botStatus,
            cameraGranted = cameraGranted,
            contactsGranted = contactsGranted,
            notificationsGranted = notificationsGranted,
            mediaAccessGranted = mediaAccessGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored
        ),
        checkedAtMillis = 1_000L
    )

    private companion object {
        const val GIB = 1_073_741_824L
    }
}
