package com.downloader.model

data class DownloadConfig(
    val url: String,
    val outputFile: String,
    val chunkCount: Int?,       // null = adaptive
    val maxRetries: Int = 3,
    val checksum: String? = null,
    val connectTimeoutSec: Int = 30,
    val readTimeoutSec: Int = 60,
)
