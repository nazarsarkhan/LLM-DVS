package com.downloader.model

import kotlinx.serialization.Serializable

@Serializable
data class Chunk(
    val index: Int,
    val startByte: Long,
    val endByte: Long,
) {
    val size: Long get() = endByte - startByte + 1
}

enum class ChunkStatus { PENDING, DOWNLOADING, DONE, FAILED }

data class ChunkProgress(
    val chunkIndex: Int,
    val totalChunks: Int,
    val bytesDownloaded: Long,
    val chunkSize: Long,
    val status: ChunkStatus,
    val speedBytesPerSec: Double = 0.0,
)
