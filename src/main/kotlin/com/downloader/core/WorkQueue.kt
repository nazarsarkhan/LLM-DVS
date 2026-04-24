package com.downloader.core

import com.downloader.model.Chunk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Thread-safe work queue for chunk downloads.
 *
 * Workers pull chunks cooperatively via [asReceiveChannel].
 * The queue is closed via [close] once all outstanding work is accounted for.
 * New chunks (e.g. from work stealing) can be enqueued at any time before close.
 */
class WorkQueue {
    private val channel = Channel<Chunk>(Channel.UNLIMITED)

    fun enqueue(chunk: Chunk) { channel.trySend(chunk) }

    fun enqueueAll(chunks: List<Chunk>) = chunks.forEach { enqueue(it) }

    /** True when there are no chunks currently waiting to be picked up. */
    val isEmpty: Boolean get() = channel.isEmpty

    fun close() = channel.close()

    /** Expose as ReceiveChannel so workers can iterate with `for (chunk in …)`. */
    fun asReceiveChannel(): ReceiveChannel<Chunk> = channel
}
