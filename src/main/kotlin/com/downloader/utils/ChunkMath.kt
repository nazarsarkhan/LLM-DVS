package com.downloader.utils

import com.downloader.model.Chunk

private const val MB = 1024L * 1024L

fun computeChunkCount(bytes: Long): Int = when {
    bytes < 1 * MB   -> 1
    bytes < 10 * MB  -> 4
    bytes < 100 * MB -> 8
    else             -> 16
}

fun computeChunks(totalBytes: Long, chunkCount: Int): List<Chunk> {
    val chunkSize = totalBytes / chunkCount
    return (0 until chunkCount).map { i ->
        val start = i * chunkSize
        val end   = if (i == chunkCount - 1) totalBytes - 1 else start + chunkSize - 1
        Chunk(index = i, startByte = start, endByte = end)
    }
}
