package com.downloader

import com.downloader.checksum.ChecksumMismatchException
import com.downloader.checksum.ChecksumVerifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertTrue

class ChecksumVerifierTest {

    @TempDir
    lateinit var tmpDir: Path

    private val verifier = ChecksumVerifier()

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `correct checksum passes`() {
        val data = "Hello, World!".toByteArray()
        val file = File(tmpDir.toFile(), "file.bin").also { it.writeBytes(data) }
        val expected = sha256Hex(data)
        verifier.verify(file, expected) // should not throw
    }

    @Test
    fun `wrong checksum throws ChecksumMismatchException`() {
        val data = "Hello, World!".toByteArray()
        val file = File(tmpDir.toFile(), "file.bin").also { it.writeBytes(data) }
        assertThrows<ChecksumMismatchException> {
            verifier.verify(file, "0".repeat(64))
        }
    }

    @Test
    fun `checksum is case-insensitive`() {
        val data = "test".toByteArray()
        val file = File(tmpDir.toFile(), "file.bin").also { it.writeBytes(data) }
        val expected = sha256Hex(data).uppercase()
        verifier.verify(file, expected) // should not throw
    }
}
