package id.my.femasaf.secondlink

import org.junit.Assert.*
import org.junit.Test

class TelegramMenuStateTest {
    @Test fun unlockExpiresAfterFiveMinutes() {
        var now = 1_000L
        val state = TelegramMenuState(clock = { now })
        state.unlock("chat", 7L)
        assertTrue(state.isUnlocked("chat", 7L))
        now += TelegramMenuState.SESSION_TTL_MS + 1
        assertFalse(state.isUnlocked("chat", 7L))
    }

    @Test fun anotherUserCannotReuseUnlock() {
        val state = TelegramMenuState { 1_000L }
        state.unlock("chat", 7L)
        assertFalse(state.isUnlocked("chat", 8L))
    }

    @Test fun pendingInputIsBoundToOwnerAndExpires() {
        var now = 1_000L
        val state = TelegramMenuState(clock = { now })
        state.beginInput("chat", 7L, InputKind.DOWNLOAD_URL)
        assertNull(state.consumeInput("chat", 8L))
        now += TelegramMenuState.SESSION_TTL_MS + 1
        assertNull(state.consumeInput("chat", 7L))
    }

    @Test fun callbackValuesAreShortAndDoNotContainSecrets() {
        val callback = TelegramKeyboard.callback("web", "status", "0123456789abcdef")
        assertTrue(callback.length <= 64)
        assertFalse(callback.contains("https"))
        assertFalse(callback.contains("com.blok"))
    }
}
