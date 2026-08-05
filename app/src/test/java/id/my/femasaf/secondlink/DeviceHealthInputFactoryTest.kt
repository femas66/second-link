package id.my.femasaf.secondlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthInputFactoryTest {

    @Test
    fun createPreservesValidPlatformValuesAndAddsBotStatus() {
        val input = DeviceHealthInputFactory.create(
            platform = platformSnapshot(),
            botStatus = "Connected"
        )

        assertEquals(72, input.batteryPercent)
        assertFalse(input.charging!!)
        assertEquals(31.5f, input.temperatureC)
        assertEquals(512L, input.storageAvailableBytes)
        assertEquals(2_048L, input.storageTotalBytes)
        assertEquals("Wi-Fi", input.networkType)
        assertTrue(input.internetValidated!!)
        assertEquals(90_000L, input.uptimeMillis)
        assertEquals("Connected", input.botStatus)
        assertFalse(input.mediaAccessGranted!!)
    }

    @Test
    fun createRejectsInvalidMeasurementsInsteadOfTurningThemIntoHealthyValues() {
        val input = DeviceHealthInputFactory.create(
            platform = platformSnapshot().copy(
                batteryPercent = 101,
                temperatureC = Float.NaN,
                storageAvailableBytes = -1L,
                storageTotalBytes = 0L,
                uptimeMillis = -1L
            ),
            botStatus = "Connected"
        )

        assertNull(input.batteryPercent)
        assertNull(input.temperatureC)
        assertNull(input.storageAvailableBytes)
        assertNull(input.storageTotalBytes)
        assertNull(input.uptimeMillis)
    }

    private fun platformSnapshot() = DeviceHealthPlatformSnapshot(
        batteryPercent = 72,
        charging = false,
        temperatureC = 31.5f,
        batteryCondition = BatteryCondition.GOOD,
        storageAvailableBytes = 512L,
        storageTotalBytes = 2_048L,
        networkType = "Wi-Fi",
        internetValidated = true,
        uptimeMillis = 90_000L,
        cameraGranted = true,
        contactsGranted = false,
        notificationsGranted = true,
        mediaAccessGranted = false,
        batteryOptimizationIgnored = false
    )
}
