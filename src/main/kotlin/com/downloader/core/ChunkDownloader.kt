package com.downloader.core

import com.downloader.model.Chunk
import com.downloader.model.ChunkProgress
import com.downloader.model.ChunkStatus
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.RandomAccessFile

class ChunkDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        chunk: Chunk,
        partFile: RandomAccessFile,
        totalChunks: Int,
        maxRetries: Int,
        progressChannel: SendChannel<ChunkProgress>,
    ) {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            try {
                downloadAttempt(url, chunk, partFile, totalChunks, progressChannel)
                return
            } catch (e: IOException) {
                lastException = e
                attempt++
                if (attempt <= maxRetries) {
                    val backoffMs = (1L shl (attempt - 1)) * 1000L  // 1s, 2s, 4s…
                    delay(backoffMs)
                }
            }
        }

        throw IOException("Chunk ${chunk.index} failed after $maxRetries retries", lastException)
    }

    private suspend fun downloadAttempt(
        url: String,
        chunk: Chunk,
        partFile: RandomAccessFile,
        totalChunks: Int,
        progressChannel: SendChannel<ChunkProgress>,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${chunk.startByte}-${chunk.endByte}")
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (resp.code != 206) {
                throw IOException("Expected 206 Partial Content, got ${resp.code} for chunk ${chunk.index}")
            }
            // RFC 7233: 206 MUST include Content-Range; validate it matches the requested range
            val contentRange = resp.header("Content-Range")
                ?: throw IOException("Missing Content-Range header for chunk ${chunk.index}")
            val rangeMatch = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").find(contentRange.trim())
                ?: throw IOException("Invalid Content-Range header '$contentRange' for chunk ${chunk.index}")
            val rangeStart = rangeMatch.groupValues[1].toLong()
            val rangeEnd   = rangeMatch.groupValues[2].toLong()
            if (rangeStart != chunk.startByte || rangeEnd != chunk.endByte) {
                throw IOException(
                    "Content-Range mismatch: expected bytes ${chunk.startByte}-${chunk.endByte}, got '$contentRange'"
                )
            }
            val body = resp.body ?: throw IOException("Empty body for chunk ${chunk.index}")
            val startTime = System.currentTimeMillis()
            var bytesRead = 0L

            body.byteStream().use { stream ->
                val buffer = ByteArray(8 * 1024)
                synchronized(partFile) {
                    partFile.seek(chunk.startByte)
                }
                while (true) {
                    val n = stream.read(buffer)
                    if (n == -1) break
                    synchronized(partFile) {
                        partFile.seek(chunk.startByte + bytesRead)
                        partFile.write(buffer, 0, n)
                    }
                    bytesRead += n
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                    val speed = bytesRead * 1000.0 / elapsed
                    progressChannel.trySend(
                        ChunkProgress(
                            chunkIndex = chunk.index,
                            totalChunks = totalChunks,
                            bytesDownloaded = bytesRead,
                            chunkSize = chunk.size,
                            status = ChunkStatus.DOWNLOADING,
                            speedBytesPerSec = speed,
                        )
                    )
                }
            }
        }
    }
}
