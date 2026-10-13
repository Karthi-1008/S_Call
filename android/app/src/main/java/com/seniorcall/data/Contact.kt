package com.seniorcall.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class Contact(
    val id: String = "",
    val name: String = "",
    val role: String = "family",
    val photoUrl: String? = null,
    val autoAnswerAllowed: Boolean = false,
    val priority: Int = 1,
    val language: String? = "en"
)

class ContactStore(private val context: Context) {
    private val gson = Gson()
    private val contactsFile: File
        get() = File(context.filesDir, "contacts_cache.json")

    fun getContacts(): List<Contact> {
        val cached = try {
            if (!contactsFile.exists()) emptyList()
            else {
                val json = contactsFile.readText()
                val type = object : TypeToken<List<Contact>>() {}.type
                gson.fromJson<List<Contact>>(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        return if (cached.isEmpty()) {
            listOf(
                Contact(
                    id = "usr_family",
                    name = "Family",
                    role = "family",
                    priority = 1,
                    autoAnswerAllowed = true
                )
            )
        } else {
            cached
        }
    }

    fun saveContacts(contacts: List<Contact>) {
        try {
            val json = gson.toJson(contacts)
            contactsFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getContact(id: String): Contact? {
        return getContacts().firstOrNull { it.id == id }
    }
}
