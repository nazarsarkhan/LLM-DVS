package com.downloader.core

import com.downloader.checksum.ChecksumVerifier
import com.downloader.model.Chunk
import com.downloader.model.ChunkProgress
import com.downloader.model.ChunkStatus
import com.downloader.model.DownloadConfig
import com.downloader.model.DownloadManifest
import com.downloader.progress.ProgressRenderer
import com.downloader.resume.ManifestManager
import com.downloader.utils.computeChunkCount
import com.downloader.utils.computeChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileDownloader(
    private val config: DownloadConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSec.toLong(), TimeUnit.SECONDS)
        .build(),
) {
    private val prober           = FileProber(client)
    private val chunkDl          = ChunkDownloader(client)
    private val assembler        = ChunkAssembler()
    private val checksumVerifier = ChecksumVerifier()

    suspend fun download() {
        val caps = prober.probe(config.url)
        if (!config.quiet) println("File size: ${formatBytes(caps.contentLength)}  Accepts-Ranges: ${caps.acceptsRanges}")

        // Resolve output path: prefer Content-Disposition filename when user didn't specify --output
        val outputPath = if (!config.outputExplicit && caps.fileName != null) caps.fileName else config.outputFile

        if (!caps.acceptsRanges) {
            if (!config.quiet) println("Server does not support range requests — falling back to single stream.")
            fallbackSingleStream(caps.contentLength, outputPath)
            return
        }

        val totalBytes  = caps.contentLength
        val chunkCount  = config.chunkCount ?: computeChunkCount(totalBytes)
        val allChunks   = computeChunks(totalBytes, chunkCount)

        val outputFile   = File(outputPath)
        val partFile     = File("$outputPath.part")
        val manifestFile = File("$outputPath.manifest.json")
        val manifestMgr  = ManifestManager(manifestFile)

        // Resume logic
        val manifest = run {
            val existing = manifestMgr.load()
            if (existing != null
                && existing.url == config.url
                && existing.totalBytes == totalBytes
                && existing.chunkCount == chunkCount
            ) {
                if (!config.quiet) println("Resuming download: ${existing.doneChunks.size}/$chunkCount chunks already done.")
                existing
            } else {
                if (existing != null && !config.quiet) println("Stale manifest detected — restarting download.")
                DownloadManifest(
                    url        = config.url,
                    totalBytes = totalBytes,
                    chunkCount = chunkCount,
                    chunks     = allChunks,
                ).also { manifestMgr.save(it) }
            }
        }

        // Pre-allocate the .part file
        RandomAccessFile(partFile, "rw").use { it.setLength(totalBytes) }

        val pendingChunks = allChunks.filter { it.index !in manifest.doneChunks }
        if (!config.quiet) println("Downloading $chunkCount chunks (${pendingChunks.size} pending)…\n")

        val progressChannel = Channel<ChunkProgress>(Channel.BUFFERED)
        val renderer        = ProgressRenderer(totalBytes)

        coroutineScope {
            val renderJob = launch(Dispatchers.Default) {
                if (config.quiet) {
                    for (p in progressChannel) { /* discard */ }
                } else {
                    renderer.render(progressChannel)
                }
            }

            RandomAccessFile(partFile, "rw").use { raf ->
                downloadWithWorkStealing(
                    pendingChunks   = pendingChunks,
                    chunkCount      = chunkCount,
                    raf             = raf,
                    manifestMgr     = manifestMgr,
                    manifest        = manifest,
                    progressChannel = progressChannel,
                )
            }

            progressChannel.close()
            renderJob.join()
        }

        assembler.assemble(partFile, outputFile, manifestFile)
        if (!config.quiet) println("Download complete → ${outputFile.absolutePath}")

        config.checksum?.let {
            if (!config.quiet) print("Verifying SHA-256 checksum… ")
            checksumVerifier.verify(outputFile, it)
            if (!config.quiet) println("OK")
        }
    }

    /**
     * Downloads [pendingChunks] using a fixed worker pool. A [WorkStealingMonitor] runs
     * concurrently: if the work queue is empty and any chunk has been running more than
     * [STEAL_THRESHOLD_MS] ms, it cancels that chunk and re-enqueues two half-sized sub-chunks,
     * keeping idle workers busy.
     */
    private suspend fun downloadWithWorkStealing(
        pendingChunks: List<Chunk>,
        chunkCount: Int,
        raf: RandomAccessFile,
        manifestMgr: ManifestManager,
        manifest: DownloadManifest,
        progressChannel: Channel<ChunkProgress>,
    ) = coroutineScope {

        val workQueue        = WorkQueue()
        val outstandingCount = AtomicInteger(pendingChunks.size)
        val subChunkCounter  = AtomicInteger(chunkCount)  // generates unique indices for sub-chunks

        // Per-chunk tracking for work stealing
        val activeJobMap   = ConcurrentHashMap<Int, Job>()   // chunkIndex → download job
        val activeChunkMap = ConcurrentHashMap<Int, Chunk>() // chunkIndex → Chunk
        val chunkStartTime = ConcurrentHashMap<Int, Long>()  // chunkIndex → start epoch ms

        workQueue.enqueueAll(pendingChunks)

        // ── Worker pool ────────────────────────────────────────────────────────
        val workerCount = pendingChunks.size.coerceAtMost(chunkCount).coerceAtLeast(1)

        val workers = (0 until workerCount).map {
            launch(Dispatchers.IO) {
                for (chunk in workQueue.asReceiveChannel()) {
                    chunkStartTime[chunk.index] = System.currentTimeMillis()
                    activeChunkMap[chunk.index] = chunk

                    // Download in a child job so we can cancel it for work stealing
                    // without cancelling this worker coroutine.
                    var downloadSucceeded = false
                    val downloadJob = launch(Dispatchers.IO) {
                        try {
                            chunkDl.download(
                                url             = config.url,
                                chunk           = chunk,
                                partFile        = raf,
                                totalChunks     = chunkCount,
                                maxRetries      = config.maxRetries,
                                progressChannel = progressChannel,
                            )
                            downloadSucceeded = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e  // must propagate so job.isCancelled is set
                        } catch (_: Exception) {
                            // IOException after exhausted retries — downloadSucceeded stays false
                        }
                    }
                    activeJobMap[chunk.index] = downloadJob
                    downloadJob.join()

                    // Clean up tracking
                    activeJobMap.remove(chunk.index)
                    activeChunkMap.remove(chunk.index)
                    chunkStartTime.remove(chunk.index)

                    when {
                        downloadJob.isCancelled -> {
                            // Stolen by work stealing monitor — outstandingCount already adjusted
                        }
                        !downloadSucceeded -> {
                            // Download failed after retries — close queue and propagate
                            workQueue.close()
                            throw java.io.IOException("Chunk ${chunk.index} failed after ${config.maxRetries} retries")
                        }
                        else -> {
                            // Normal completion
                            progressChannel.trySend(
                                ChunkProgress(
                                    chunkIndex      = chunk.index,
                                    totalChunks     = chunkCount,
                                    bytesDownloaded = chunk.size,
                                    chunkSize       = chunk.size,
                                    status          = ChunkStatus.DONE,
                                )
                            )
                            // Only persist to manifest for original chunks (index < chunkCount)
                            if (chunk.index < chunkCount) {
                                manifestMgr.markChunkDone(manifest, chunk.index)
                            }
                            if (outstandingCount.decrementAndGet() == 0) {
                                workQueue.close()
                            }
                        }
                    }
                }
            }
        }

        // ── Work stealing monitor ──────────────────────────────────────────────
        val monitorJob = launch {
            while (isActive) {
                delay(STEAL_POLL_MS)
                val now = System.currentTimeMillis()

                // Only steal when the queue is empty (workers will become idle) and
                // there is at least one active download to steal.
                // Only steal original chunks (index < chunkCount); sub-chunks (index >= chunkCount)
                // are never re-stolen to prevent cascading delays.
                if (!workQueue.isEmpty || activeChunkMap.isEmpty()) continue

                val stealTarget = activeChunkMap.values
                    .filter { chunk ->
                        val elapsed = now - (chunkStartTime[chunk.index] ?: now)
                        elapsed > STEAL_THRESHOLD_MS && chunk.index < chunkCount
                    }
                    .maxByOrNull { chunk ->
                        now - (chunkStartTime[chunk.index] ?: now)
                    }

                if (stealTarget != null) {
                    val midPoint = (stealTarget.startByte + stealTarget.endByte) / 2
                    if (midPoint <= stealTarget.startByte) continue  // too small to split

                    val sub1 = Chunk(
                        index     = subChunkCounter.getAndIncrement(),
                        startByte = stealTarget.startByte,
                        endByte   = midPoint,
                    )
                    val sub2 = Chunk(
                        index     = subChunkCounter.getAndIncrement(),
                        startByte = midPoint + 1,
                        endByte   = stealTarget.endByte,
                    )

                    // Increment BEFORE cancelling so outstandingCount never reaches 0 prematurely.
                    // The original chunk's decrement is skipped (isCancelled path), so net change:
                    //   +1 (this increment) for replacing 1 outstanding with 2 new ones.
                    outstandingCount.incrementAndGet()
                    activeJobMap[stealTarget.index]?.cancel()
                    workQueue.enqueue(sub1)
                    workQueue.enqueue(sub2)
                }
            }
        }

        workers.forEach { it.join() }
        monitorJob.cancel()
    }

    private fun fallbackSingleStream(expectedBytes: Long, outputPath: String) {
        val outputFile = File(outputPath)
        val request = Request.Builder().url(config.url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("Empty body")
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    if (!config.quiet) println("Downloaded ${formatBytes(total)}")
                }
            }
        }
        if (!config.quiet) println("Download complete → ${outputFile.absolutePath}")
        config.checksum?.let {
            if (!config.quiet) print("Verifying SHA-256 checksum… ")
            checksumVerifier.verify(outputFile, it)
            if (!config.quiet) println("OK")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000     -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000         -> "%.2f KB".format(bytes / 1_000.0)
        else                   -> "$bytes B"
    }

    companion object {
        /** How often the work stealing monitor checks for slow chunks (ms). */
        const val STEAL_POLL_MS = 1_000L
        /** A chunk running longer than this is a steal candidate (ms). */
        const val STEAL_THRESHOLD_MS = 3_000L
    }
}
