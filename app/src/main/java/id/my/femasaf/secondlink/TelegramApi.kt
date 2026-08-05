package id.my.femasaf.secondlink

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface TelegramApi {
    @FormUrlEncoded
    @POST("sendMessage")
    suspend fun sendMessage(
        @Field("chat_id") chatId: String,
        @Field("text") text: String,
        @Field("parse_mode") parseMode: String = "Markdown",
        @Field("reply_markup") replyMarkup: String? = null
    ): Response<Unit>

    @FormUrlEncoded
    @POST("editMessageText")
    suspend fun editMessageText(
        @Field("chat_id") chatId: String,
        @Field("message_id") messageId: Long,
        @Field("text") text: String,
        @Field("reply_markup") replyMarkup: String? = null
    ): Response<Unit>

    @FormUrlEncoded
    @POST("answerCallbackQuery")
    suspend fun answerCallbackQuery(
        @Field("callback_query_id") id: String,
        @Field("text") text: String? = null,
        @Field("show_alert") showAlert: Boolean = false
    ): Response<Unit>

    @GET("getUpdates")
    suspend fun getUpdates(
        @Query("offset") offset: Long?,
        @Query("timeout") timeout: Int = 30
    ): Response<TelegramUpdateResponse>

    @Multipart
    @POST("sendPhoto")
    suspend fun sendPhoto(
        @Part("chat_id") chatId: RequestBody,
        @Part photo: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null
    ): Response<Unit>

    @FormUrlEncoded
    @POST("deleteMessage")
    suspend fun deleteMessage(
        @Field("chat_id") chatId: String,
        @Field("message_id") messageId: Long
    ): Response<Unit>
}
