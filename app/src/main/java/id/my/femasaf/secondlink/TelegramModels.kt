package id.my.femasaf.secondlink

import com.google.gson.annotations.SerializedName

data class TelegramUpdateResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("result") val result: List<TelegramUpdate>
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    @SerializedName("message") val message: TelegramMessage?,
    @SerializedName("callback_query") val callbackQuery: TelegramCallbackQuery? = null
)

data class TelegramCallbackQuery(
    @SerializedName("id") val id: String,
    @SerializedName("from") val from: TelegramUser,
    @SerializedName("message") val message: TelegramMessage?,
    @SerializedName("data") val data: String?
)

data class TelegramInlineKeyboardButton(
    @SerializedName("text") val text: String,
    @SerializedName("callback_data") val callbackData: String?
)

data class TelegramReplyMarkup(
    @SerializedName("inline_keyboard") val inlineKeyboard: List<List<TelegramInlineKeyboardButton>>
)

data class TelegramMessage(
    @SerializedName("message_id") val messageId: Long,
    @SerializedName("from") val from: TelegramUser?,
    @SerializedName("chat") val chat: TelegramChat,
    @SerializedName("text") val text: String?,
    @SerializedName("date") val date: Long = 0
)

data class TelegramUser(
    @SerializedName("id") val id: Long,
    @SerializedName("first_name") val firstName: String
)

data class TelegramChat(
    @SerializedName("id") val id: Long,
    @SerializedName("type") val type: String = ""
)
