package com.lumasr.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun CoroutineScope.launchWithForegroundDeadline(
    timeoutMs: Long,
    operation: suspend () -> Unit,
    stopService: () -> Unit
): Job {
    require(timeoutMs > 0L) { "Foreground timeout budget must be positive" }
    val stopped = AtomicBoolean(false)
    fun stopOnce() {
        if (stopped.compareAndSet(false, true)) stopService()
    }
    val deadline = launch(start = CoroutineStart.UNDISPATCHED) {
        delay(timeoutMs)
        stopOnce()
    }
    return launch {
        try {
            operation()
        } finally {
            deadline.cancel()
            stopOnce()
        }
    }
}
