package com.lexidex.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slug generation mirrors `create_personal_term`/`slugify` in backend/lexidex_api.py. A personal
 * slug is stored in the user database and referenced by favorites, history and collections, so a
 * change here would strand existing rows - hence pinning the exact shape rather than a pattern.
 */
class PersonalTermIdentityTest {

    private val uid = "usr_0123456789abcdef0123456789abcdef"

    @Test
    fun `a slug carries origin, language, title and the uid fragment`() {
        assertEquals("personal-es-tango--01234567", personalTermSlug(uid, "es", "Tango"))
    }

    @Test
    fun `accents are folded and spaces become single hyphens`() {
        assertEquals(
            "personal-es-canon-del-colorado--01234567",
            personalTermSlug(uid, "es", "Cañón   del Colorado"),
        )
    }

    @Test
    fun `punctuation runs collapse into one hyphen and never trail`() {
        assertEquals(
            "personal-en-rock-roll--01234567",
            personalTermSlug(uid, "en", "  Rock & roll!! "),
        )
    }

    @Test
    fun `a title with nothing sluggable falls back to termino`() {
        assertEquals("personal-es-termino--01234567", personalTermSlug(uid, "es", "¿?"))
        assertEquals("personal-es-termino--01234567", personalTermSlug(uid, "es", "разум"))
    }

    @Test
    fun `the title part is capped at 72 characters`() {
        val slug = personalTermSlug(uid, "es", "a".repeat(100))
        assertEquals("personal-es-${"a".repeat(72)}--01234567", slug)
    }

    @Test
    fun `a fresh uid is the backend's usr_ prefix plus a bare uuid`() {
        val fresh = newPersonalTermUid()
        assertTrue(fresh, fresh.startsWith("usr_"))
        assertEquals(36, fresh.length)
        assertTrue(fresh, fresh.drop(4).all { it in "0123456789abcdef" })
        assertTrue(newPersonalTermUid() != fresh)
    }
}
