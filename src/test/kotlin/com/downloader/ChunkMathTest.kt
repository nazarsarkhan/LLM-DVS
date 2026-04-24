package com.downloader

import com.downloader.utils.computeChunkCount
import com.downloader.utils.computeChunks
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkMathTest {

    private val MB = 1024L * 1024L

    // ── computeChunkCount ────────────────────────────────────────────────────

    @Test
    fun `0 bytes returns 1 chunk`() = assertEquals(1, computeChunkCount(0))

    @Test
    fun `exactly 1 MB returns 4 chunks`() = assertEquals(4, computeChunkCount(1 * MB))

    @Test
    fun `1 MB minus 1 byte returns 1 chunk`() = assertEquals(1, computeChunkCount(1 * MB - 1))

    @Test
    fun `exactly 10 MB returns 8 chunks`() = assertEquals(8, computeChunkCount(10 * MB))

    @Test
    fun `exactly 100 MB returns 16 chunks`() = assertEquals(16, computeChunkCount(100 * MB))

    // ── computeChunks invariants ─────────────────────────────────────────────

    @Test
    fun `sum of all chunk sizes equals totalBytes`() {
        val total = 1_000_003L  // not evenly divisible
        val chunks = computeChunks(total, 4)
        assertEquals(total, chunks.sumOf { it.size })
    }

    @Test
    fun `no gaps between consecutive chunks`() {
        val chunks = computeChunks(1024, 4)
        for (n in 0 until chunks.size - 1) {
            assertEquals(
                chunks[n].endByte + 1, chunks[n + 1].startByte,
                "Gap between chunk $n and ${n + 1}"
            )
        }
    }

    @Test
    fun `last chunk endByte equals totalBytes minus 1`() {
        val total = 9999L
        val chunks = computeChunks(total, 7)
        assertEquals(total - 1, chunks.last().endByte)
    }

    @Test
    fun `single byte file produces one chunk spanning byte 0`() {
        val chunks = computeChunks(1L, 1)
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].startByte)
        assertEquals(0L, chunks[0].endByte)
        assertEquals(1L, chunks[0].size)
    }

    @Test
    fun `chunk indices are zero-based and contiguous`() {
        val chunks = computeChunks(512, 4)
        chunks.forEachIndexed { i, chunk -> assertEquals(i, chunk.index) }
    }

    @Test
    fun `first chunk starts at byte 0`() {
        val chunks = computeChunks(2048, 8)
        assertEquals(0L, chunks.first().startByte)
    }

    @Test
    fun `last chunk absorbs remainder bytes`() {
        // 10 bytes / 3 chunks: sizes should be 3, 3, 4 (last gets remainder)
        val chunks = computeChunks(10, 3)
        assertTrue(chunks.last().size >= chunks.first().size)
        assertEquals(10L, chunks.sumOf { it.size })
    }
}
