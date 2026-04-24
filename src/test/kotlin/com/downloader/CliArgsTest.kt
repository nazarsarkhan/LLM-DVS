package com.downloader

import com.downloader.cli.parseArgs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliArgsTest {

    private fun parse(vararg args: String) = parseArgs(arrayOf(*args), testMode = true)

    // ── Happy-path parsing ───────────────────────────────────────────────────

    @Test
    fun `URL only - output derived from path`() {
        val result = parse("https://example.com/myfile.bin")
        assertEquals("https://example.com/myfile.bin", result.config.url)
        assertEquals("myfile.bin", result.config.outputFile)
        assertNull(result.config.chunkCount)
        assertEquals(3, result.config.maxRetries)
        assertNull(result.config.checksum)
    }

    @Test
    fun `URL with no path segment falls back to downloaded_file`() {
        val result = parse("https://example.com/")
        assertEquals("downloaded_file", result.config.outputFile)
    }

    @Test
    fun `--output flag sets outputFile`() {
        val result = parse("https://example.com/f.bin", "--output", "/tmp/out.bin")
        assertEquals("/tmp/out.bin", result.config.outputFile)
    }

    @Test
    fun `--chunks flag parsed correctly`() {
        val result = parse("https://example.com/f.bin", "--chunks", "8")
        assertEquals(8, result.config.chunkCount)
    }

    @Test
    fun `--retries flag parsed correctly`() {
        val result = parse("https://example.com/f.bin", "--retries", "5")
        assertEquals(5, result.config.maxRetries)
    }

    @Test
    fun `--checksum flag parsed correctly`() {
        val result = parse("https://example.com/f.bin", "--checksum", "abc123")
        assertEquals("abc123", result.config.checksum)
    }

    @Test
    fun `--timeout flag sets both connect and read timeouts`() {
        val result = parse("https://example.com/f.bin", "--timeout", "45")
        assertEquals(45, result.config.connectTimeoutSec)
        assertEquals(45, result.config.readTimeoutSec)
    }

    @Test
    fun `default connect timeout is 30 and read timeout is 60`() {
        val result = parse("https://example.com/f.bin")
        assertEquals(30, result.config.connectTimeoutSec)
        assertEquals(60, result.config.readTimeoutSec)
    }

    // ── Error cases ──────────────────────────────────────────────────────────

    @Test
    fun `no args throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parseArgs(emptyArray(), testMode = true) }
    }

    @Test
    fun `non-URL first arg throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("not-a-url") }
    }

    @Test
    fun `unknown flag throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("https://example.com/f.bin", "--unknown") }
    }

    @Test
    fun `missing value after --output throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("https://example.com/f.bin", "--output") }
    }

    @Test
    fun `missing value after --chunks throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("https://example.com/f.bin", "--chunks") }
    }

    @Test
    fun `non-integer value for --chunks throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("https://example.com/f.bin", "--chunks", "abc") }
    }

    @Test
    fun `missing value after --timeout throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { parse("https://example.com/f.bin", "--timeout") }
    }
}
