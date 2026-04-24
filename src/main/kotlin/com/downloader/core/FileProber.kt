package com.downloader.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ServerCapabilities(
    val contentLength: Long,
    val acceptsRanges: Boolean,
    val fileName: String?,
)

class FileProber(private val client: OkHttpClient) {

    fun probe(url: String): ServerCapabilities {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("Server returned ${it.code} for HEAD $url")
            }
            val contentLength = it.header("Content-Length")?.toLongOrNull()?.takeIf { cl -> cl > 0 }
                ?: throw IOException("Server did not return a positive Content-Length for $url")
            val acceptsRanges = it.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            val fileName = it.header("Content-Disposition")
                ?.let { cd -> Regex("""filename="?([^";\s]+)"?""").find(cd)?.groupValues?.get(1) }
            return ServerCapabilities(contentLength, acceptsRanges, fileName)
        }
    }
}
