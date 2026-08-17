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
        val state = TelegramMenuState(clock = { now })
        state.bindMenu("99", 7L)
        val router = TelegramMenuRouter(state, recordingDispatcher(), { now })

        val result = router.handle(callback(8L))

        assertTrue(result is RouterResult.Answer)
        assertEquals("Aksi tidak diizinkan.", (result as RouterResult.Answer).text)
    }

    @Test
    fun validCallbackDispatchesCommandDirectly() = runBlocking {
        val state = TelegramMenuState(clock = { now })
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(state, dispatcher, { now })

        val result = router.handle(callback(7L, data = "v1:media:play"))

        assertEquals(listOf("/media play"), dispatcher.commands)
        assertTrue(result is RouterResult.EditMenu)
    }

    @Test
    fun staleMenuVersionIsRejected() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v2:status"))

        assertTrue(result is RouterResult.Answer)
        assertTrue((result as RouterResult.Answer).text!!.contains("diperbarui"))
    }

    @Test
    fun doctorButtonDispatchesPermissionCheck() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), dispatcher, { now })

        val result = router.handle(callback(7L, data = "v1:doctor"))

        assertEquals(listOf("/doctor"), dispatcher.commands)
        assertTrue(result is RouterResult.EditMenu)
    }

    @Test
    fun webButtonsDispatchCorrectCommands() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), dispatcher, { now })

        router.handle(callback(7L, data = "v1:web:status"))
        router.handle(callback(7L, data = "v1:web:on"))
        router.handle(callback(7L, data = "v1:web:off"))

        assertEquals(listOf("/web", "/web on", "/web off"), dispatcher.commands)
    }

    @Test
    fun webMenuButtonOpensWebScreen() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v1:web"))

        assertTrue(result is RouterResult.EditMenu)
        assertTrue((result as RouterResult.EditMenu).text.contains("Web"))
    }

    @Test
    fun eventButtonsDispatchCorrectCommands() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), dispatcher, { now })

        router.handle(callback(7L, data = "v1:event:list"))

        assertEquals(listOf("/events"), dispatcher.commands)
    }

    @Test
    fun eventMenuButtonOpensEventScreen() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v1:event"))

        assertTrue(result is RouterResult.EditMenu)
        assertTrue((result as RouterResult.EditMenu).text.contains("Event"))
    }

    @Test
    fun jadwalButtonsDispatchCorrectCommands() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), dispatcher, { now })

        router.handle(callback(7L, data = "v1:jadwal:today"))
        router.handle(callback(7L, data = "v1:jadwal:tomorrow"))
        router.handle(callback(7L, data = "v1:jadwal:all"))
        router.handle(callback(7L, data = "v1:jadwal:day_1"))

        assertEquals(listOf("/jadwal today", "/jadwal tomorrow", "/jadwal all", "/jadwal senin"), dispatcher.commands)
    }

    @Test
    fun jadwalMenuButtonOpensJadwalScreen() = runBlocking {
        val router = TelegramMenuRouter(TelegramMenuState(clock = { now }), recordingDispatcher(), { now })

        val result = router.handle(callback(7L, data = "v1:jadwal"))

        assertTrue(result is RouterResult.EditMenu)
        assertTrue((result as RouterResult.EditMenu).text.contains("Jadwal"))
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
