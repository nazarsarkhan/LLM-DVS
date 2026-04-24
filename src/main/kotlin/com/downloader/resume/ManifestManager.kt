package com.downloader.resume

import com.downloader.model.DownloadManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ManifestManager(private val manifestFile: File) {

    private val json = Json { prettyPrint = true }

    fun load(): DownloadManifest? = try {
        if (manifestFile.exists()) json.decodeFromString(manifestFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun save(manifest: DownloadManifest) {
        manifestFile.writeText(json.encodeToString(manifest))
    }

    @Synchronized
    fun markChunkDone(manifest: DownloadManifest, chunkIndex: Int) {
        manifest.doneChunks.add(chunkIndex)
        save(manifest)
    }

    fun delete() {
        manifestFile.delete()
    }
}
