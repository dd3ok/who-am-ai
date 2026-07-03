package com.dd3ok.whoamai.common.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MongoUriLogSanitizerTest {

    @Test
    fun `describes atlas uri without leaking credentials`() {
        val description = MongoUriLogSanitizer.describe(
            "mongodb+srv://user:secret@example.mongodb.net/whoamai?retryWrites=true&w=majority"
        )

        assertEquals("hosts=example.mongodb.net,database=whoamai", description)
        assertFalse(description.contains("user"))
        assertFalse(description.contains("secret"))
    }

    @Test
    fun `describes blank and invalid uri without echoing raw input`() {
        assertEquals("empty", MongoUriLogSanitizer.describe(""))
        assertEquals("invalid", MongoUriLogSanitizer.describe("not a mongo uri with secret"))
    }

    @Test
    fun `detects localhost mongo uri`() {
        assertTrue(MongoUriLogSanitizer.isLocalhost("mongodb://localhost:27017/whoamai"))
        assertTrue(MongoUriLogSanitizer.isLocalhost("mongodb://127.0.0.1:27017/whoamai"))
        assertFalse(MongoUriLogSanitizer.isLocalhost("mongodb+srv://user:secret@example.mongodb.net/whoamai"))
    }
}
