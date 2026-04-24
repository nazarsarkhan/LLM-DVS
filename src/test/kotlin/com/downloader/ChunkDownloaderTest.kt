package com.downloader

import com.downloader.core.ChunkDownloader
import com.downloader.model.Chunk
import com.downloader.model.ChunkProgress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.test.assertEquals

class ChunkDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: ChunkDownloader

    @TempDir
    lateinit var tmpDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ChunkDownloader(OkHttpClient())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun makePartFile(size: Long): RandomAccessFile {
        val f = File(tmpDir.toFile(), "test.part")
        return RandomAccessFile(f, "rw").also { it.setLength(size) }
    }

    @Test
    fun `206 happy path writes correct bytes`() = runBlocking {
        val data = "ABCDEFGHIJ".toByteArray()
        val buf = Buffer().also { it.write(data) }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-9/10")
                .setBody(buf)
        )

        val chunk = Chunk(index = 0, startByte = 0, endByte = 9)
        val raf = makePartFile(10)
        val channel = Channel<ChunkProgress>(Channel.BUFFERED)

        raf.use {
            downloader.download(
                url             = server.url("/file.bin").toString(),
                chunk           = chunk,
                partFile        = it,
                totalChunks     = 1,
                maxRetries      = 0,
                progressChannel = channel,
            )
        }
        channel.close()

        val result = File(tmpDir.toFile(), "test.part").readBytes()
        assertEquals(data.toList(), result.toList())
    }

    @Test
    fun `correct Range header is sent`() = runBlocking {
        val data = "HELLO".toByteArray()
        val buf = Buffer().also { it.write(data) }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 100-104/200")
                .setBody(buf)
        )

        val chunk = Chunk(index = 0, startByte = 100, endByte = 104)
        val raf = makePartFile(200)
        val channel = Channel<ChunkProgress>(Channel.BUFFERED)

        raf.use {
            downloader.download(
                url             = server.url("/file.bin").toString(),
                chunk           = chunk,
                partFile        = it,
                totalChunks     = 1,
                maxRetries      = 0,
                progressChannel = channel,
            )
        }
        channel.close()

        val request = server.takeRequest()
        assertEquals("bytes=100-104", request.getHeader("Range"))
    }

    @Test
    fun `retries on 500 then succeeds on 206`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val data = "OK".toByteArray()
        val buf = Buffer().also { it.write(data) }
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-1/2").setBody(buf))

        val chunk = Chunk(index = 0, startByte = 0, endByte = 1)
        val raf = makePartFile(2)
        val channel = Channel<ChunkProgress>(Channel.BUFFERED)

        raf.use {
            downloader.download(
                url             = server.url("/file.bin").toString(),
                chunk           = chunk,
                partFile        = it,
                totalChunks     = 1,
                maxRetries      = 3,
                progressChannel = channel,
            )
        }
        channel.close()

        val result = File(tmpDir.toFile(), "test.part").readBytes()
        assertEquals(data.toList(), result.take(2))
    }

    @Test
    fun `exhausted retries throws IOException`() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(500)) }

        val chunk = Chunk(index = 0, startByte = 0, endByte = 9)
        val raf = makePartFile(10)
        val channel = Channel<ChunkProgress>(Channel.BUFFERED)

        assertThrows<IOException> {
            runBlocking {
                raf.use {
                    downloader.download(
                        url             = server.url("/file.bin").toString(),
                        chunk           = chunk,
                        partFile        = it,
                        totalChunks     = 1,
                        maxRetries      = 2,
                        progressChannel = channel,
                    )
                }
            }
        }
        channel.close()
    }
}
