package id.my.femasaf.secondlink

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedInputFlowTest {
    private val message = TelegramMessage(
        messageId = 31L,
        from = TelegramUser(7L, "Owner"),
        chat = TelegramChat(99L, "private"),
        text = "secret",
        date = 10L
    )

    @Test
    fun validAccessCodeUnlocksOnlyAfterDeletion() = runBlocking {
        val state = TelegramMenuState { 10_000L }
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long) = CommandResult("ok")
            },
            { 10_000L },
            verifyAccessCode = { it == "secret" },
            deleteCodeMessage = { _, id -> id == 31L }
        )
        router.startUnlock("99", 7L)
        router.acceptTextInput(message)
        assertTrue(state.isUnlocked("99", 7L, 10_000L))
    }

    @Test
    fun failedDeletionKeepsSessionLocked() = runBlocking {
        val state = TelegramMenuState { 10_000L }
        val router = TelegramMenuRouter(
            state,
            object : CommandDispatcher {
                override suspend fun dispatch(command: String, chatId: String, userId: Long) = CommandResult("ok")
            },
            { 10_000L },
            verifyAccessCode = { true },
            deleteCodeMessage = { _, _ -> false }
        )
        router.startUnlock("99", 7L)
        router.acceptTextInput(message)
        assertFalse(state.isUnlocked("99", 7L, 10_000L))
    }
}
