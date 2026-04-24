package com.downloader

import com.downloader.model.Chunk
import com.downloader.model.DownloadManifest
import com.downloader.resume.ManifestManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestManagerTest {

    @TempDir
    lateinit var tmpDir: Path

    private fun makeManifest() = DownloadManifest(
        url        = "https://example.com/file.bin",
        totalBytes = 1024,
        chunkCount = 4,
        chunks     = listOf(
            Chunk(0, 0, 255),
            Chunk(1, 256, 511),
            Chunk(2, 512, 767),
            Chunk(3, 768, 1023),
        ),
    )

    @Test
    fun `round-trip save and load`() {
        val mgr      = ManifestManager(File(tmpDir.toFile(), "test.manifest.json"))
        val manifest = makeManifest()
        mgr.save(manifest)
        val loaded = mgr.load()
        assertNotNull(loaded)
        assertEquals(manifest.url, loaded.url)
        assertEquals(manifest.totalBytes, loaded.totalBytes)
        assertEquals(manifest.chunks.size, loaded.chunks.size)
    }

    @Test
    fun `markChunkDone persists done chunk`() {
        val mgr      = ManifestManager(File(tmpDir.toFile(), "test.manifest.json"))
        val manifest = makeManifest()
        mgr.save(manifest)
        mgr.markChunkDone(manifest, 2)

        val loaded = mgr.load()
        assertNotNull(loaded)
        assertTrue(2 in loaded.doneChunks)
    }

    @Test
    fun `missing file returns null`() {
        val mgr = ManifestManager(File(tmpDir.toFile(), "nonexistent.json"))
        assertNull(mgr.load())
    }

    @Test
    fun `corrupt JSON returns null`() {
        val file = File(tmpDir.toFile(), "corrupt.json").also { it.writeText("{{invalid}}") }
        val mgr  = ManifestManager(file)
        assertNull(mgr.load())
    }
}
