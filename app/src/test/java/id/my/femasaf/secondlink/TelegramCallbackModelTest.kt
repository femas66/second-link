package id.my.femasaf.secondlink

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramCallbackModelTest {
    private val gson = Gson()

    @Test
    fun callbackQueryIsParsedFromUpdate() {
        val update = gson.fromJson(
            """{
                "update_id": 42,
                "callback_query": {
                    "id": "abc123",
                    "from": {"id": 5562277502, "first_name": "Ada"},
                    "message": {
                        "message_id": 17,
                        "from": {"id": 1, "first_name": "Bot"},
                        "chat": {"id": 99, "type": "private"},
                        "text": "Menu"
                    },
                    "data": "v1:menu:status"
                }
            }""",
            TelegramUpdate::class.java
        )

        assertEquals("abc123", update.callbackQuery?.id)
        assertEquals("v1:menu:status", update.callbackQuery?.data)
        assertEquals(5562277502L, update.callbackQuery?.from?.id)
        assertEquals(17L, update.callbackQuery?.message?.messageId)
        assertNull(update.message)
    }

    @Test
    fun replyMarkupSerializesTelegramFieldNames() {
        val markup = TelegramReplyMarkup(
            listOf(
                listOf(
                    TelegramInlineKeyboardButton("Status", "v1:menu:status"),
                    TelegramInlineKeyboardButton("More", null)
                )
            )
        )

        val json = JsonParser.parseString(gson.toJson(markup)).asJsonObject
        val button = json.getAsJsonArray("inline_keyboard")[0].asJsonArray

        assertNotNull(json.get("inline_keyboard"))
        assertEquals("Status", button[0].asJsonObject.get("text").asString)
        assertEquals("v1:menu:status", button[0].asJsonObject.get("callback_data").asString)
        assertFalse(button[1].asJsonObject.has("callback_data"))
    }
}
