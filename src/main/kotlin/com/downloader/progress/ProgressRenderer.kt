package com.downloader.progress

import com.downloader.model.ChunkProgress
import com.downloader.model.ChunkStatus
import kotlinx.coroutines.channels.ReceiveChannel

class ProgressRenderer(private val totalBytes: Long) {

    private val BAR_WIDTH = 20
    private val ANSI_RESET  = "\u001B[0m"
    private val ANSI_GREEN  = "\u001B[32m"
    private val ANSI_CYAN   = "\u001B[36m"
    private val ANSI_YELLOW = "\u001B[33m"
    private val ANSI_UP     = "\u001B[A"
    private val ANSI_CLEAR  = "\u001B[2K"

    // Per-chunk state: bytesDownloaded and actual chunk sizes
    private val chunkBytes  = mutableMapOf<Int, Long>()
    private val chunkSizes  = mutableMapOf<Int, Long>()
    private val chunkStatus = mutableMapOf<Int, ChunkStatus>()
    private var totalChunks = 0
    private var startTime = System.currentTimeMillis()
    private var firstRender = true

    suspend fun render(channel: ReceiveChannel<ChunkProgress>) {
        startTime = System.currentTimeMillis()
        for (progress in channel) {
            totalChunks = progress.totalChunks
            chunkBytes[progress.chunkIndex] = progress.bytesDownloaded
            chunkSizes[progress.chunkIndex] = progress.chunkSize
            chunkStatus[progress.chunkIndex] = progress.status
            redraw()
        }
        // Final redraw showing completion
        chunkStatus.keys.forEach { chunkStatus[it] = ChunkStatus.DONE }
        redraw()
        println()
    }

    private fun redraw() {
        val lines = mutableListOf<String>()
        val totalDownloaded = chunkBytes.values.sumOf { it }
        val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val speedBps = totalDownloaded * 1000.0 / elapsedMs
        val remaining = totalBytes - totalDownloaded
        val etaSec = if (speedBps > 0) remaining / speedBps else 0.0

        // Per-chunk bars
        for (i in 0 until totalChunks) {
            val downloaded = chunkBytes.getOrDefault(i, 0L)
            val chunkSize = chunkSizes.getOrDefault(i, totalBytes / totalChunks.coerceAtLeast(1))
            val pct = if (chunkSize > 0) (downloaded * 100 / chunkSize).coerceIn(0, 100) else 0
            val status = chunkStatus.getOrDefault(i, ChunkStatus.PENDING)
            val filled = (BAR_WIDTH * pct / 100).toInt()
            val bar = "#".repeat(filled) + "-".repeat(BAR_WIDTH - filled)
            val color = when (status) {
                ChunkStatus.DONE        -> ANSI_GREEN
                ChunkStatus.DOWNLOADING -> ANSI_CYAN
                ChunkStatus.FAILED      -> "\u001B[31m"
                ChunkStatus.PENDING     -> ANSI_YELLOW
            }
            lines += "$color[${i.toString().padStart(2)}] [$bar] ${pct.toString().padStart(3)}%$ANSI_RESET"
        }

        // Summary line
        val totalPct = if (totalBytes > 0) (totalDownloaded * 100 / totalBytes).coerceIn(0, 100) else 0
        val speedStr = formatSpeed(speedBps)
        val etaStr   = formatEta(etaSec)
        lines += "${ANSI_GREEN}Total: ${totalPct}%  Speed: $speedStr  ETA: $etaStr$ANSI_RESET"

        if (!firstRender) {
            // Move cursor up by number of lines we printed last time
            val up = (totalChunks + 1)
            print(ANSI_UP.repeat(up))
        }
        firstRender = false

        for (line in lines) {
            print("\r$ANSI_CLEAR$line\n")
        }
    }

    private fun formatSpeed(bps: Double): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000)
        bps >= 1_000     -> "%.1f KB/s".format(bps / 1_000)
        else             -> "%.0f B/s".format(bps)
    }

    private fun formatEta(sec: Double): String = when {
        sec <= 0 || sec.isInfinite() || sec.isNaN() -> "--"
        sec < 60   -> "${sec.toInt()}s"
        sec < 3600 -> "${(sec / 60).toInt()}m ${(sec % 60).toInt()}s"
        else       -> "${(sec / 3600).toInt()}h ${((sec % 3600) / 60).toInt()}m"
    }
}
