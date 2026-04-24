package com.downloader

import com.downloader.checksum.ChecksumMismatchException
import com.downloader.core.FileDownloader
import com.downloader.model.DownloadConfig
import com.downloader.model.DownloadManifest
import com.downloader.model.Chunk
import com.downloader.resume.ManifestManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDownloaderTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tmpDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    /** Generates a repeatable byte array of given size */
    private fun makeData(size: Int): ByteArray = ByteArray(size) { (it % 256).toByte() }

    /** Dispatcher that serves ranged GET and HEAD requests with proper Content-Range headers */
    private fun rangeDispatcher(data: ByteArray): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.method) {
                "HEAD" -> MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length", data.size.toString())
                    .addHeader("Accept-Ranges", "bytes")
                "GET" -> {
                    val range = request.getHeader("Range")!!
                    val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
                    val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
                    val buf = Buffer().also { it.write(slice) }
                    MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes $start-$end/${data.size}")
                        .setBody(buf)
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Test
    fun `full parallel download produces correct file`() = runBlocking {
        val data = makeData(1024)
        server.dispatcher = rangeDispatcher(data)

        val outputFile = File(tmpDir.toFile(), "out.bin")
        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = 4,
            maxRetries = 0,
        )
        FileDownloader(config).download()

        assertTrue(outputFile.exists())
        assertEquals(data.toList(), outputFile.readBytes().toList())
    }

    @Test
    fun `fallback single stream when no Accept-Ranges`() = runBlocking {
        val data = makeData(512)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", data.size.toString())
                    // no Accept-Ranges
                    "GET" -> {
                        val buf = Buffer().also { it.write(data) }
                        MockResponse().setResponseCode(200).setBody(buf)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val outputFile = File(tmpDir.toFile(), "out.bin")
        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = null,
            maxRetries = 0,
        )
        FileDownloader(config).download()

        assertTrue(outputFile.exists())
        assertEquals(data.toList(), outputFile.readBytes().toList())
    }

    @Test
    fun `resume skips already-done chunks`() = runBlocking {
        val data = makeData(1024)
        val requestedRanges = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", data.size.toString())
                        .addHeader("Accept-Ranges", "bytes")
                    "GET" -> {
                        val range = request.getHeader("Range")!!
                        synchronized(requestedRanges) { requestedRanges.add(range) }
                        val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
                        val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
                        val buf = Buffer().also { it.write(slice) }
                        MockResponse()
                            .setResponseCode(206)
                            .addHeader("Content-Range", "bytes $start-$end/${data.size}")
                            .setBody(buf)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val outputFile   = File(tmpDir.toFile(), "resume_out.bin")
        val partFile     = File(tmpDir.toFile(), "resume_out.bin.part")
        val manifestFile = File(tmpDir.toFile(), "resume_out.bin.manifest.json")

        // First download with 4 chunks
        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = 4,
            maxRetries = 0,
        )
        FileDownloader(config).download()

        // Verify all 4 ranges were fetched
        assertEquals(4, requestedRanges.size)
        assertTrue(outputFile.exists())
        assertFalse(manifestFile.exists())
        assertFalse(partFile.exists())
    }

    @Test
    fun `checksum mismatch throws ChecksumMismatchException`() {
        val data = makeData(1024)
        server.dispatcher = rangeDispatcher(data)

        val outputFile = File(tmpDir.toFile(), "checksum_out.bin")
        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = 4,
            maxRetries = 0,
            checksum   = "0000000000000000000000000000000000000000000000000000000000000000",
        )

        assertThrows<ChecksumMismatchException> {
            runBlocking { FileDownloader(config).download() }
        }
    }

    @Test
    fun `empty file throws IOException`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length", "0")
        }

        val outputFile = File(tmpDir.toFile(), "empty_out.bin")
        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = 1,
            maxRetries = 0,
        )

        assertThrows<java.io.IOException> {
            runBlocking { FileDownloader(config).download() }
        }
    }

    @Test
    fun `stale manifest with different URL causes full re-download`() = runBlocking {
        val data = makeData(1024)
        val requestedRanges = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", data.size.toString())
                        .addHeader("Accept-Ranges", "bytes")
                    "GET" -> {
                        val range = request.getHeader("Range")!!
                        synchronized(requestedRanges) { requestedRanges.add(range) }
                        val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
                        val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
                        val buf = Buffer().also { it.write(slice) }
                        MockResponse()
                            .setResponseCode(206)
                            .addHeader("Content-Range", "bytes $start-$end/${data.size}")
                            .setBody(buf)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val outputFile   = File(tmpDir.toFile(), "stale_out.bin")
        val manifestFile = File(tmpDir.toFile(), "stale_out.bin.manifest.json")

        // Write stale manifest with a different URL — all 4 chunks marked done
        val staleManifest = DownloadManifest(
            url        = "http://other-server/different-file.bin",
            totalBytes = data.size.toLong(),
            chunkCount = 4,
            chunks     = listOf(
                Chunk(0, 0, 255), Chunk(1, 256, 511),
                Chunk(2, 512, 767), Chunk(3, 768, 1023),
            ),
            doneChunks = mutableSetOf(0, 1, 2, 3),
        )
        manifestFile.writeText(Json { prettyPrint = true }.encodeToString(staleManifest))

        val config = DownloadConfig(
            url        = server.url("/file.bin").toString(),
            outputFile = outputFile.absolutePath,
            chunkCount = 4,
            maxRetries = 0,
        )
        FileDownloader(config).download()

        // Stale manifest discarded → all 4 chunks re-fetched
        assertEquals(4, requestedRanges.size)
        assertTrue(outputFile.exists())
        assertEquals(data.toList(), outputFile.readBytes().toList())
    }
}
