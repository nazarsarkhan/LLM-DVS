package com.downloader.checksum

import java.io.File
import java.security.MessageDigest

class ChecksumVerifier {

    fun verify(file: File, expectedSha256Hex: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256Hex.trim(), ignoreCase = true)) {
            throw ChecksumMismatchException(
                "SHA-256 mismatch!\n  expected: $expectedSha256Hex\n  actual:   $actual"
            )
        }
    }
}

class ChecksumMismatchException(message: String) : Exception(message)
