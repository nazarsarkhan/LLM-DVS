package com.downloader

import com.downloader.core.FileDownloader
import com.downloader.model.DownloadConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkStealingTest {

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

    private fun makeData(size: Int): ByteArray = ByteArray(size) { (it % 256).toByte() }

    /**
     * Verifies that work stealing correctly handles a slow chunk:
     *
     * Setup: 4-chunk download where chunk 0 (bytes 0-255) is served with a 4-second delay.
     * Chunks 1-3 finish quickly. The monitor detects chunk 0 is still running after
     * [STEAL_THRESHOLD_MS], cancels it, and re-enqueues two sub-chunks (0-127 and 128-255).
     * The final file must be byte-perfect.
     *
     * A @Timeout of 15 seconds ensures we don't hang if work stealing stops working.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `slow chunk is stolen and output file is byte-perfect`() = runBlocking {
        val data = makeData(1024)
        val chunk0RequestCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.method) {
                    "HEAD" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", data.size.toString())
                        .addHeader("Accept-Ranges", "bytes")

                    "GET" -> {
                        val range = request.getHeader("Range")!!
                        val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
                        val isChunk0Region = start < 256 && end < 256

                        val response = MockResponse()
                            .setResponseCode(206)
                            .addHeader("Content-Range", "bytes $start-$end/${data.size}")

                        if (isChunk0Region) {
                            // Throttle: delay each chunk-0-region response to trigger work stealing
                            chunk0RequestCount.incrementAndGet()
                            response.setBodyDelay(4_500, TimeUnit.MILLISECONDS)
                        }

                        val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
                        response.setBody(Buffer().also { it.write(slice) })
                    }

                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val outputFile = File(tmpDir.toFile(), "ws_out.bin")
        val config = DownloadConfig(
            url            = server.url("/file.bin").toString(),
            outputFile     = outputFile.absolutePath,
            chunkCount     = 4,
            maxRetries     = 0,
            readTimeoutSec = 10,
        )

        FileDownloader(config).download()

        assertTrue(outputFile.exists(), "Output file must exist")
        assertEquals(data.toList(), outputFile.readBytes().toList(), "File content must be byte-perfect")
        // Chunk 0 region should have been requested more than once (stolen → re-downloaded as sub-chunks)
        assertTrue(chunk0RequestCount.get() > 1, "Chunk 0 region should be requested multiple times due to work stealing")
    }
}
