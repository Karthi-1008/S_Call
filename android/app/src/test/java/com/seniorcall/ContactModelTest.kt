package com.seniorcall

import com.google.gson.Gson
import com.seniorcall.data.Contact
import org.junit.Assert.*
import org.junit.Test

class ContactModelTest {
    private val gson = Gson()

    @Test
    fun testContactSerialization() {
        val contact = Contact(
            id = "usr_123",
            name = "Priya",
            role = "family",
            photoUrl = null,
            autoAnswerAllowed = true,
            priority = 1,
            language = "hi"
        )
        val json = gson.toJson(contact)
        assertTrue(json.contains("\"name\":\"Priya\""))
        assertTrue(json.contains("\"autoAnswerAllowed\":true"))

        val parsed = gson.fromJson(json, Contact::class.java)
        assertEquals("usr_123", parsed.id)
        assertEquals("Priya", parsed.name)
        assertTrue(parsed.autoAnswerAllowed)
        assertEquals("hi", parsed.language)
    }
}
