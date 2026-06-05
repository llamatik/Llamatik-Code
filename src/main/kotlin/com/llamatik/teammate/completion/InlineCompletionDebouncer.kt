package com.llamatik.teammate.completion

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Debouncer for inline completion requests.
 * Cancels stale pending requests when a new keystroke arrives before debounce fires.
 */
class InlineCompletionDebouncer(private val debounceMs: Int) {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "llamatik-inline-completion-debouncer").also { it.isDaemon = true }
        }

    private val pendingFuture = AtomicReference<Future<*>?>()

    fun schedule(action: Runnable) {
        // Cancel any existing pending request
        pendingFuture.getAndSet(null)?.cancel(false)

        val future = executor.schedule({
            action.run()
        }, debounceMs.toLong(), TimeUnit.MILLISECONDS)

        pendingFuture.set(future)
    }

    fun cancel() {
        pendingFuture.getAndSet(null)?.cancel(false)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }
}
