package id.my.femasaf.secondlink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMenuRouterTest {
    private val now = 10_000L

    private fun callback(userId: Long, chatId: Long = 99L, data: String = "v1:status") =
        TelegramCallbackQuery(
            id = "callback-1",
            from = TelegramUser(userId, "Test"),
            message = TelegramMessage(17L, null, TelegramChat(chatId, "private"), "Menu", now / 1000L),
            data = data
        )

    @Test
    fun foreignUserCallbackIsIgnored() = runBlocking {
        val state = TelegramMenuState { now }
        state.bindMenu("99", 7L)
        val router = TelegramMenuRouter(state, recordingDispatcher(), { now })

        val result = router.handle(callback(8L))

        assertTrue(result is RouterResult.Answer)
        assertEquals("Aksi tidak diizinkan.", (result as RouterResult.Answer).text)
    }

    @Test
    fun sensitiveCallbackRequiresUnlock() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState { now }, recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v1:media:play"))

        assertTrue(result is RouterResult.Answer)
        assertTrue((result as RouterResult.Answer).text!!.contains("Buka akses"))
    }

    @Test
    fun staleMenuVersionIsRejected() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState { now }, recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v2:status"))

        assertTrue(result is RouterResult.Answer)
        assertTrue((result as RouterResult.Answer).text!!.contains("diperbarui"))
    }

    @Test
    fun validCallbackDispatchesWhitelistedCommand() = runBlocking {
        val state = TelegramMenuState { now }
        state.unlock("99", 7L, now)
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(state, dispatcher, { now })

        val result = router.handle(callback(7L, data = "v1:media:play"))

        assertEquals(listOf("/media play"), dispatcher.commands)
        assertTrue(result is RouterResult.EditMenu)
    }

    private class RecordingDispatcher : CommandDispatcher {
        val commands = mutableListOf<String>()
        override suspend fun dispatch(command: String, chatId: String, userId: Long): CommandResult {
            commands += command
            return CommandResult("ok")
        }
    }

    private fun recordingDispatcher(): CommandDispatcher = RecordingDispatcher()
}
