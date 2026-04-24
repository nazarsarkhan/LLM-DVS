package com.downloader.model

data class DownloadConfig(
    val url: String,
    val outputFile: String,
    val chunkCount: Int?,       // null = adaptive
    val maxRetries: Int = 3,
    val checksum: String? = null,
    val connectTimeoutSec: Int = 30,
    val readTimeoutSec: Int = 60,
    val quiet: Boolean = false,
    val outputExplicit: Boolean = false, // true when --output was provided by the user
)
