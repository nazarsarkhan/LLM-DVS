package com.downloader

import com.downloader.core.ChunkAssembler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkAssemblerTest {

    @TempDir
    lateinit var tmpDir: Path

    @Test
    fun `assemble renames part to output and deletes manifest`() {
        val data         = "Hello, World!".toByteArray()
        val partFile     = File(tmpDir.toFile(), "file.bin.part").also { it.writeBytes(data) }
        val outputFile   = File(tmpDir.toFile(), "file.bin")
        val manifestFile = File(tmpDir.toFile(), "file.bin.manifest.json").also { it.writeText("{}") }

        ChunkAssembler().assemble(partFile, outputFile, manifestFile)

        assertTrue(outputFile.exists(), "Output file should exist")
        assertContentEquals(data, outputFile.readBytes())
        assertFalse(partFile.exists(), ".part file should be deleted")
        assertFalse(manifestFile.exists(), "Manifest should be deleted")
    }
}
