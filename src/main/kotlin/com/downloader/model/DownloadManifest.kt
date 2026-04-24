package com.downloader.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadManifest(
    val url: String,
    val totalBytes: Long,
    val chunkCount: Int,
    val chunks: List<Chunk>,
    val doneChunks: MutableSet<Int> = mutableSetOf(),
)
