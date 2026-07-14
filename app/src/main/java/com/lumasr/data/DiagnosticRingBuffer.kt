package com.lumasr.data

import com.lumasr.domain.UpscaleErrorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

enum class DiagnosticEvent {
    PROCESSING_STARTED,
    PROCESSING_FINISHED,
    PROCESSING_FAILED,
    FALLBACK_SELECTED
}

enum class DiagnosticCapabilityBucket {
    GPU_AVAILABLE,
    GPU_UNAVAILABLE,
    MEMORY_LOW,
    MEMORY_STANDARD,
    MEMORY_HIGH,
    IMAGE_SMALL,
    IMAGE_MEDIUM,
    IMAGE_LARGE,
    IMAGE_EXTREME
}

enum class DiagnosticDurationBucket {
    PREPARE,
    MODEL_LOAD,
    INFERENCE,
    SAVE,
    TOTAL
}

enum class DiagnosticFallbackReason {
    VULKAN_OUT_OF_MEMORY,
    VULKAN_UNAVAILABLE,
    MODEL_UNSUPPORTED,
    CPU_REQUESTED
}

data class DiagnosticEntry(
    val timestampEpochMs: Long,
    val event: DiagnosticEvent,
    val capabilityBuckets: Set<DiagnosticCapabilityBucket> = emptySet(),
    val durationsMs: Map<DiagnosticDurationBucket, Long> = emptyMap(),
    val errorCode: UpscaleErrorCode? = null,
    val fallbackReason: DiagnosticFallbackReason? = null
) {
    init {
        require(timestampEpochMs >= 0) { "Diagnostic timestamp must be non-negative" }
        require(durationsMs.values.all { it >= 0 }) { "Diagnostic durations must be non-negative" }
    }
}

class DiagnosticRingBuffer(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()

    suspend fun append(entry: DiagnosticEntry) = withContext(ioDispatcher) {
        mutex.withLock {
            val newLine = (encode(entry) + "\n").toByteArray(Charsets.UTF_8)
            check(newLine.size <= MAX_BYTES) { "Diagnostic entry exceeds ring capacity" }

            val lines = ArrayDeque<ByteArray>()
            var totalBytes = 0
            if (file.isFile) {
                file.readLines(Charsets.UTF_8)
                    .filter(String::isNotBlank)
                    .forEach { existing ->
                        val line = (existing + "\n").toByteArray(Charsets.UTF_8)
                        lines.addLast(line)
                        totalBytes += line.size
                    }
            }
            lines.addLast(newLine)
            totalBytes += newLine.size
            while (totalBytes > MAX_BYTES) {
                totalBytes -= lines.removeFirst().size
            }

            val output = ByteArrayOutputStream(totalBytes)
            lines.forEach(output::write)
            writeAtomic(file, output.toByteArray())
        }
    }

    suspend fun exportTo(destination: File) = withContext(ioDispatcher) {
        mutex.withLock {
            val bytes = if (file.isFile) file.readBytes() else byteArrayOf()
            writeAtomic(destination, bytes)
        }
    }

    private fun encode(entry: DiagnosticEntry): String = buildJsonObject {
        put("timestampEpochMs", entry.timestampEpochMs)
        put("event", entry.event.name)
        put("capabilityBuckets", buildJsonArray {
            entry.capabilityBuckets.map { it.name }.sorted().forEach(::add)
        })
        put("durationsMs", buildJsonObject {
            entry.durationsMs.toSortedMap().forEach { (bucket, durationMs) ->
                put(bucket.name, durationMs)
            }
        })
        put("errorCode", entry.errorCode?.name)
        put("fallbackReason", entry.fallbackReason?.name)
    }.toString()

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = requireNotNull(target.absoluteFile.parentFile) { "Diagnostic file needs a parent directory" }
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create diagnostic directory" }
        val temporary = File.createTempFile(target.name, ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAX_BYTES: Int = 102_400
    }
}
