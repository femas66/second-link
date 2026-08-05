package id.my.femasaf.secondlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthLoadStateTest {

    @Test
    fun refreshFailureKeepsTheLastSuccessfulSnapshotVisible() {
        val snapshot = snapshot(1_000L)
        val current = DeviceHealthLoadState(snapshot = snapshot, isRefreshing = true)

        val failed = DeviceHealthLoadReducer.failed(current)

        assertSame(snapshot, failed.snapshot)
        assertFalse(failed.isRefreshing)
        assertEquals("Pemeriksaan gagal. Coba lagi.", failed.error)
    }

    @Test
    fun refreshStartClearsOldErrorWithoutRemovingSnapshot() {
        val snapshot = snapshot(2_000L)
        val current = DeviceHealthLoadState(
            snapshot = snapshot,
            isRefreshing = false,
            error = "Pemeriksaan gagal. Coba lagi."
        )

        val refreshing = DeviceHealthLoadReducer.started(current)

        assertSame(snapshot, refreshing.snapshot)
        assertTrue(refreshing.isRefreshing)
        assertNull(refreshing.error)
    }

    @Test
    fun refreshSuccessReplacesSnapshotAndClearsLoadingState() {
        val oldSnapshot = snapshot(3_000L)
        val newSnapshot = snapshot(4_000L)

        val success = DeviceHealthLoadReducer.succeeded(
            DeviceHealthLoadState(oldSnapshot, isRefreshing = true),
            newSnapshot
        )

        assertSame(newSnapshot, success.snapshot)
        assertFalse(success.isRefreshing)
        assertNull(success.error)
    }

    private fun snapshot(time: Long) = DeviceHealthSnapshot(
        checkedAtMillis = time,
        overallLevel = HealthLevel.GOOD,
        items = emptyList()
    )
}
