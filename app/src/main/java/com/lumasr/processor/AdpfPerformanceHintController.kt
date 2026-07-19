package com.lumasr.processor

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

interface AdpfHintSessionHandle : AutoCloseable {
    fun reportActualWorkDuration(durationNanos: Long)
    override fun close()
}

fun interface AdpfHintSessionFactory {
    fun open(targetDurationNanos: Long): AdpfHintSessionHandle?
}

class AdpfWorkSession internal constructor(
    private val handle: AdpfHintSessionHandle?
) : AutoCloseable {
    val isActive: Boolean
        get() = handle != null

    fun reportActualWorkDuration(durationNanos: Long) {
        if (durationNanos <= 0L) return
        runCatching { handle?.reportActualWorkDuration(durationNanos) }
    }

    override fun close() {
        runCatching { handle?.close() }
    }
}

class AdpfPerformanceHintController(
    private val sessionFactory: AdpfHintSessionFactory,
    private val targetDurationNanos: Long = DEFAULT_TARGET_DURATION_NANOS
) {
    init {
        require(targetDurationNanos > 0L) { "ADPF target duration must be positive" }
    }

    fun open(): AdpfWorkSession = AdpfWorkSession(
        runCatching { sessionFactory.open(targetDurationNanos) }.getOrNull()
    )

    companion object {
        const val DEFAULT_TARGET_DURATION_NANOS = 50_000_000L
    }
}

class AndroidAdpfHintSessionFactory(
    context: Context,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val threadIdProvider: () -> Int = Process::myTid
) : AdpfHintSessionFactory {
    private val appContext = context.applicationContext

    override fun open(targetDurationNanos: Long): AdpfHintSessionHandle? {
        if (sdkIntProvider() < Build.VERSION_CODES.S) return null
        return openApi31(targetDurationNanos)
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun openApi31(targetDurationNanos: Long): AdpfHintSessionHandle? {
        return runCatching {
            val manager = appContext.getSystemService(PerformanceHintManager::class.java)
                ?: return null
            val session = manager.createHintSession(
                intArrayOf(threadIdProvider()),
                targetDurationNanos
            ) ?: return null
            object : AdpfHintSessionHandle {
                override fun reportActualWorkDuration(durationNanos: Long) {
                    session.reportActualWorkDuration(durationNanos)
                }

                override fun close() {
                    session.close()
                }
            }
        }.getOrNull()
    }
}
