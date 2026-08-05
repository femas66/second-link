package id.my.femasaf.secondlink

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramKeyboardTest {
    @Test fun everyMainMenuButtonUsesKnownAction() {
        val buttons = TelegramKeyboard.main().inlineKeyboard.flatten()
        assertTrue(buttons.map { it.text }.containsAll(listOf("Status", "Media", "Kamera", "Perangkat", "Jadwal", "File", "Keamanan", "Tutup Menu")))
        assertTrue(buttons.all { it.callbackData!!.startsWith("v1:") })
    }

    @Test fun mediaScreenContainsAllSupportedControls() {
        val actions = TelegramKeyboard.render(MenuScreen.MEDIA, MenuRenderState(mediaSessionCount = 2))
            .inlineKeyboard.flatten().mapNotNull { it.callbackData }
        listOf("status", "sessions", "play", "pause", "toggle", "next", "previous", "rewind", "fastforward", "stop", "queue", "search", "speed", "volume", "repeat", "shuffle", "actions", "select_1").forEach {
            assertTrue("missing $it", actions.contains("v1:media:$it"))
        }
    }

    @Test fun sensitiveButtonsDoNotEmbedSecretsOrRawInput() {
        val callbacks = MenuScreen.values().flatMap { TelegramKeyboard.render(it, MenuRenderState(mediaSessionCount = 8)).inlineKeyboard.flatten() }.mapNotNull { it.callbackData }
        callbacks.forEach {
            assertTrue(it.length <= 64)
            assertFalse(it.contains("https", ignoreCase = true))
            assertFalse(it.contains("#kode", ignoreCase = true))
            assertFalse(it.contains("/media", ignoreCase = true))
        }
    }
}
