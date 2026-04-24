package com.downloader.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ChunkAssembler {

    /**
     * Moves the completed .part file to the final output path using NIO,
     * then deletes the manifest file.
     */
    fun assemble(partFile: File, outputFile: File, manifestFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // Cross-device or FS doesn't support atomic move — retry without it
            try {
                Files.move(
                    partFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e2: Exception) {
                throw IOException("Failed to move ${partFile.name} → ${outputFile.name}: ${e2.message}", e2)
            }
        }
        manifestFile.delete()
    }
}
