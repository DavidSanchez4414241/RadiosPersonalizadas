package com.deivela.radioslocales

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testSanitizeUrl() {
        val raw = "(https://graph.facebook.com/los40/picture?width=200&height=200"
        val clean = sanitizeUrl(raw)
        assertEquals("https://graph.facebook.com/los40/picture?width=200&height=200", clean)

        val rawWithQuotes = "\"https://example.com/stream.mp3\""
        assertEquals("https://example.com/stream.mp3", sanitizeUrl(rawWithQuotes))
    }

    @Test
    fun testRadioDisplayName() {
        val r1 = Radio(name = "Cadena 100", streamUrl = "https://example.com/stream", frequency = "89.7 FM")
        assertEquals("89.7 FM / Cadena 100", r1.displayName)

        val r2 = Radio(name = "Cadena 100", streamUrl = "https://example.com/stream", frequency = "")
        assertEquals("Cadena 100", r2.displayName)
    }
}
