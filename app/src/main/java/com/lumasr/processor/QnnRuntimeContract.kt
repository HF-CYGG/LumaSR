package com.lumasr.processor

import com.lumasr.domain.QnnCapabilitySnapshot
import com.lumasr.domain.QnnExecutionConfig
import com.lumasr.domain.QnnPerformanceMode
import com.lumasr.domain.QnnPrecision
import java.util.LinkedHashMap

data class QnnContextKey(
    val graphKey: String,
    val providerBuildId: String,
    val htpArchitecture: String,
    val skelBuildId: String,
    val dlcSha256: String,
    val modelArtifactHash: String,
    val precision: QnnPrecision,
    val tileSize: Int,
    val performanceMode: QnnPerformanceMode
) {
    init {
        QnnExecutionConfig(graphKey, precision, tileSize, 1, performanceMode)
        requireNormalized(providerBuildId, "QNN provider build id")
        require(Regex("v[0-9]{2}").matches(htpArchitecture)) {
            "QNN HTP architecture must use the vNN form"
        }
        requireNormalized(skelBuildId, "QNN Skel build id")
        require(SHA256.matches(dlcSha256)) { "QNN DLC hash must be lowercase SHA-256" }
        require(SHA256.matches(modelArtifactHash)) { "QNN model hash must be lowercase SHA-256" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")

        fun requireNormalized(value: String, label: String) {
            require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
                "$label must be normalized"
            }
        }
    }
}

interface QnnContextHandle : AutoCloseable {
    val byteSize: Long
    override fun close()
}

class QnnContextCorruptionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Replaceable QAIRT boundary. Production code can bind it to a native function table while unit
 * tests retain deterministic ownership, cancellation and recovery semantics without SDK binaries.
 */
interface QnnRuntimeFunctionTable {
    fun probeCapabilities(): QnnCapabilitySnapshot
    fun createContext(key: QnnContextKey, contextBinary: ByteArray): QnnContextHandle
    fun execute(context: QnnContextHandle, inputRgb: ByteArray, outputRgb: ByteArray)
    fun cancel(taskId: String)
}

class QnnContextCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : AutoCloseable {
    private val entries = LinkedHashMap<QnnContextKey, QnnContextHandle>(16, 0.75f, true)
    var currentBytes: Long = 0L
        private set

    init {
        require(maxBytes > 0L) { "QNN context cache budget must be positive" }
    }

    @Synchronized
    fun acquire(key: QnnContextKey, create: () -> QnnContextHandle): QnnContextHandle {
        entries[key]?.let { return it }
        val context = create()
        require(context.byteSize in 1..maxBytes) {
            runCatching(context::close)
            "QNN context exceeds cache budget"
        }
        while (entries.isNotEmpty() && currentBytes > maxBytes - context.byteSize) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            currentBytes -= eldest.value.byteSize
            runCatching(eldest.value::close)
        }
        entries[key] = context
        currentBytes += context.byteSize
        return context
    }

    fun <T> executeWithSingleRebuild(
        key: QnnContextKey,
        create: () -> QnnContextHandle,
        execute: (QnnContextHandle) -> T
    ): T {
        val first = acquire(key, create)
        try {
            return execute(first)
        } catch (_: QnnContextCorruptionException) {
            invalidate(key)
        }

        val rebuilt = acquire(key, create)
        return try {
            execute(rebuilt)
        } catch (second: QnnContextCorruptionException) {
            invalidate(key)
            throw IllegalStateException("QNN context rebuild failed", second)
        }
    }

    @Synchronized
    fun invalidate(key: QnnContextKey): Boolean {
        val removed = entries.remove(key) ?: return false
        currentBytes -= removed.byteSize
        runCatching(removed::close)
        return true
    }

    @Synchronized
    override fun close() {
        entries.values.forEach { runCatching(it::close) }
        entries.clear()
        currentBytes = 0L
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1_024L * 1_024L
    }
}
