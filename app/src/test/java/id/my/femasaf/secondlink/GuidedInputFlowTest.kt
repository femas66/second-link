package id.my.femasaf.secondlink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedInputFlowTest {
    @Test
    fun guidedReminderInputDispatchesReminderCommand() = runBlocking {
        val state = TelegramMenuState(clock = { 10_000L })
        var dispatchedCommand: String? = null
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult {
                    dispatchedCommand = command
                    return CommandResult("ok")
                }
            },
            { 10_000L }
        )
        state.beginInput("99", 7L, InputKind.REMINDER_DURATION, now = 10_000L)
        val durationMessage = TelegramMessage(
            messageId = 32L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "10m",
            date = 10L
        )
        val result1 = router.acceptTextInput(durationMessage)
        assertTrue(result1 is RouterResult.SendMessage)
        assertTrue(state.hasPendingInput("99", 7L, 10_000L))

        val reminderMessage = TelegramMessage(
            messageId = 33L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "Beli kopi",
            date = 10L
        )
        val result2 = router.acceptTextInput(reminderMessage)
        assertTrue(result2 is RouterResult.SendMessage)
        assertEquals("/remind 10m Beli kopi", dispatchedCommand)
    }

    @Test
    fun cancelInputAbortsPendingFlow() = runBlocking {
        val state = TelegramMenuState(clock = { 10_000L })
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long) = CommandResult("ok")
            },
            { 10_000L }
        )
        state.beginInput("99", 7L, InputKind.DOWNLOAD_URL, now = 10_000L)
        val cancelMessage = TelegramMessage(
            messageId = 33L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "/cancel",
            date = 10L
        )
        val result = router.acceptTextInput(cancelMessage)
        assertTrue(result is RouterResult.SendMessage)
        assertFalse(state.hasPendingInput("99", 7L, 10_000L))
    }

    @Test
    fun guidedEventInputDispatchesEventAddCommand() = runBlocking {
        val state = TelegramMenuState(clock = { 10_000L })
        var dispatchedCommand: String? = null
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult {
                    dispatchedCommand = command
                    return CommandResult("ok")
                }
            },
            { 10_000L }
        )
        state.beginInput("99", 7L, InputKind.EVENT_TIME, now = 10_000L)
        val timeMessage = TelegramMessage(
            messageId = 34L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "2026-08-17 15:30",
            date = 10L
        )
        val result1 = router.acceptTextInput(timeMessage)
        assertTrue(result1 is RouterResult.SendMessage)
        assertTrue(state.hasPendingInput("99", 7L, 10_000L))

        val titleMessage = TelegramMessage(
            messageId = 35L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "Rapat Proyek | Ruang 204",
            date = 10L
        )
        val result2 = router.acceptTextInput(titleMessage)
        assertTrue(result2 is RouterResult.SendMessage)
        assertEquals("/event_add 2026-08-17 15:30 Rapat Proyek | Ruang 204", dispatchedCommand)
    }

    @Test
    fun guidedJadwalInputDispatchesJadwalAddCommand() = runBlocking {
        val state = TelegramMenuState(clock = { 10_000L })
        var dispatchedCommand: String? = null
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult {
                    dispatchedCommand = command
                    return CommandResult("ok")
                }
            },
            { 10_000L }
        )
        state.beginInput("99", 7L, InputKind.JADWAL_DAY, now = 10_000L)
        val dayMessage = TelegramMessage(
            messageId = 36L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "Senin",
            date = 10L
        )
        val result1 = router.acceptTextInput(dayMessage)
        assertTrue(result1 is RouterResult.SendMessage)
        assertTrue(state.hasPendingInput("99", 7L, 10_000L))

        val timeMessage = TelegramMessage(
            messageId = 37L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "08:00-10:30",
            date = 10L
        )
        val result2 = router.acceptTextInput(timeMessage)
        assertTrue(result2 is RouterResult.SendMessage)
        assertTrue(state.hasPendingInput("99", 7L, 10_000L))

        val detailsMessage = TelegramMessage(
            messageId = 38L,
            from = TelegramUser(7L, "Owner"),
            chat = TelegramChat(99L, "private"),
            text = "Pemrograman Web | Lab 2 | Pak Budi",
            date = 10L
        )
        val result3 = router.acceptTextInput(detailsMessage)
        assertTrue(result3 is RouterResult.SendMessage)
        assertEquals("/jadwal_add 1 08:00-10:30 Pemrograman Web | Lab 2 | Pak Budi", dispatchedCommand)
    }
}
