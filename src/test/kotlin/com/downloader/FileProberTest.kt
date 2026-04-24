package com.downloader

import com.downloader.core.FileProber
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileProberTest {

    private lateinit var server: MockWebServer
    private lateinit var prober: FileProber

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        prober = FileProber(OkHttpClient())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `HEAD with Accept-Ranges returns correct capabilities`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Length", "10485760")
                .addHeader("Accept-Ranges", "bytes")
        )
        val caps = prober.probe(server.url("/file.bin").toString())
        assertEquals(10_485_760L, caps.contentLength)
        assertTrue(caps.acceptsRanges)
    }

    @Test
    fun `HEAD without Accept-Ranges returns acceptsRanges=false`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Length", "1024")
        )
        val caps = prober.probe(server.url("/file.bin").toString())
        assertEquals(1024L, caps.contentLength)
        assertFalse(caps.acceptsRanges)
    }

    @Test
    fun `HEAD returning 404 throws IOException`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows<IOException> {
            prober.probe(server.url("/missing.bin").toString())
        }
    }

    @Test
    fun `HEAD without Content-Length throws IOException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Accept-Ranges", "bytes")
        )
        assertThrows<IOException> {
            prober.probe(server.url("/no-length.bin").toString())
        }
    }
}
