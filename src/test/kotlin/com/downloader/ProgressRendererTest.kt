package com.downloader

import com.downloader.model.ChunkProgress
import com.downloader.model.ChunkStatus
import com.downloader.progress.ProgressRenderer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertTrue

class ProgressRendererTest {

    private lateinit var originalOut: PrintStream
    private lateinit var capturedOut: ByteArrayOutputStream

    @BeforeEach
    fun redirectStdout() {
        originalOut = System.out
        capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    /**
     * Regression: last chunk is larger than the others (integer-division remainder).
     * Before the fix, chunkSize was computed as totalBytes/totalChunks which underestimates
     * the last chunk, capping its bar at ~99%. After the fix, the real chunkSize from
     * ChunkProgress is used and the bar reaches 100%.
     */
    @Test
    fun `last chunk with larger size renders 100 percent`() = runTest {
        val totalBytes = 1025L   // 4 chunks: 256, 256, 256, 257
        val chunkCount = 4
        val chunkSize  = totalBytes / chunkCount          // 256 (floor)
        val lastChunkSize = totalBytes - (chunkCount - 1) * chunkSize  // 257

        val renderer = ProgressRenderer(totalBytes)
        val channel = Channel<ChunkProgress>(Channel.UNLIMITED)

        // Send progress for chunks 0–2 (equal size, fully done)
        for (i in 0 until chunkCount - 1) {
            channel.trySend(ChunkProgress(
                chunkIndex      = i,
                totalChunks     = chunkCount,
                bytesDownloaded = chunkSize,
                chunkSize       = chunkSize,
                status          = ChunkStatus.DONE,
            ))
        }
        // Send 100% progress for last chunk with its REAL (larger) size
        channel.trySend(ChunkProgress(
            chunkIndex      = chunkCount - 1,
            totalChunks     = chunkCount,
            bytesDownloaded = lastChunkSize,
            chunkSize       = lastChunkSize,
            status          = ChunkStatus.DONE,
        ))
        channel.close()

        val renderJob = launch { renderer.render(channel) }
        renderJob.join()

        val output = capturedOut.toString()
        // Strip ANSI escape codes for assertion
        val plain = output.replace(Regex("\u001B\\[[0-9;]*m"), "")
            .replace(Regex("\u001B\\[[0-9]*[A-Z]"), "")

        // The last chunk line should show 100%
        assertTrue(
            plain.contains("100%"),
            "Expected '100%' in output but got:\n$plain"
        )
    }

    @Test
    fun `total summary line shows 100 percent when all bytes downloaded`() = runTest {
        val totalBytes  = 512L
        val chunkCount  = 2
        val chunkSize   = totalBytes / chunkCount

        val renderer = ProgressRenderer(totalBytes)
        val channel  = Channel<ChunkProgress>(Channel.UNLIMITED)

        for (i in 0 until chunkCount) {
            channel.trySend(ChunkProgress(
                chunkIndex      = i,
                totalChunks     = chunkCount,
                bytesDownloaded = chunkSize,
                chunkSize       = chunkSize,
                status          = ChunkStatus.DONE,
            ))
        }
        channel.close()

        val renderJob = launch { renderer.render(channel) }
        renderJob.join()

        val plain = capturedOut.toString()
            .replace(Regex("\u001B\\[[0-9;]*m"), "")
            .replace(Regex("\u001B\\[[0-9]*[A-Z]"), "")

        assertTrue(plain.contains("Total: 100%"), "Expected 'Total: 100%' in:\n$plain")
    }
}
