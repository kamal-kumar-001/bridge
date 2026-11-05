package com.bridge.app.core.network

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One bounded, ordered writer. UI callers enqueue only; file workers wait for backpressure. */
class FrameWriter(private val onFailure: (Exception) -> Unit) : Closeable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "Bridge-TLS-writer").apply { isDaemon = true } })

    fun write(wait: Boolean = false, frame: () -> Unit) {
        val task = java.util.concurrent.FutureTask<Unit> {
            try { frame() } catch (error: Exception) { onFailure(error); throw error }
        }
        try {
            executor.execute(task)
            if (wait) task.get()
        } catch (error: Exception) {
            if (error !is java.util.concurrent.ExecutionException) onFailure(error)
            throw error
        }
    }

    override fun close() {
        executor.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(false) }
    }
}
