package com.lumasr.data

import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FileProcessingTaskRepository(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProcessingTaskRepository {
    private val mutex = Mutex()

    override suspend fun save(task: ProcessingTask) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDirectory()
            writeAtomic(taskFile(task.id), ProcessingTaskJson.encode(task).toByteArray(Charsets.UTF_8))
        }
    }

    override suspend fun find(taskId: String): ProcessingTask? = withContext(ioDispatcher) {
        mutex.withLock {
            readRecord(taskFile(taskId))
        }
    }

    override suspend fun list(): List<ProcessingTask> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!directory.exists()) {
                emptyList()
            } else {
                directory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.extension == RECORD_EXTENSION }
                    .mapNotNull(::readRecord)
                    .sortedBy(ProcessingTask::id)
                    .toList()
            }
        }
    }

    override suspend fun delete(taskId: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val file = taskFile(taskId)
            !file.exists() || file.delete()
        }
    }

    private fun readRecord(file: File): ProcessingTask? {
        if (!file.isFile) return null
        return try {
            val task = ProcessingTaskJson.decode(file.readText(Charsets.UTF_8))
            check(file.name == taskFile(task.id).name) { "Task id does not match record name" }
            task
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            quarantine(file)
            null
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create task repository directory" }
    }

    private fun taskFile(taskId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(taskId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.$RECORD_EXTENSION")
    }

    private fun quarantine(file: File) {
        try {
            val destination = File(directory, "${file.name}.${System.currentTimeMillis()}.$CORRUPT_EXTENSION")
            moveReplacing(file, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A damaged record remains ignored even if the filesystem cannot quarantine it.
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile(target.name, ".$TEMP_EXTENSION", target.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val RECORD_EXTENSION = "json"
        const val CORRUPT_EXTENSION = "corrupt"
        const val TEMP_EXTENSION = "tmp"
    }
}

private object ProcessingTaskJson {
    private const val SCHEMA_VERSION = 1
    private val json = Json

    fun encode(task: ProcessingTask): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", task.id)
        put("copiedInputPath", task.copiedInputPath)
        put("outputPath", task.outputPath)
        put("state", task.state.name)
        put("imageIndex", task.imageIndex)
        put("imageCount", task.imageCount)
        put("completedExtremeTileIndexes", buildJsonArray {
            task.completedExtremeTileIndexes.sorted().forEach(::add)
        })
        put("createdAtEpochMs", task.createdAtEpochMs)
        put("updatedAtEpochMs", task.updatedAtEpochMs)
        put("terminalAtEpochMs", task.terminalAtEpochMs)
        put("retryableErrorCode", task.retryableErrorCode)
        put("executionProfileKey", task.executionProfileKey)
    }.toString()

    fun decode(value: String): ProcessingTask {
        val record = json.parseToJsonElement(value).jsonObject
        check(record.requiredInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported task schema" }
        return ProcessingTask(
            id = record.requiredString("id"),
            copiedInputPath = record.requiredString("copiedInputPath"),
            outputPath = record.requiredString("outputPath"),
            state = ProcessingTaskState.valueOf(record.requiredString("state")),
            imageIndex = record.requiredInt("imageIndex"),
            imageCount = record.requiredInt("imageCount"),
            completedExtremeTileIndexes = record.getValue("completedExtremeTileIndexes")
                .jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.int },
            createdAtEpochMs = record.requiredLong("createdAtEpochMs"),
            updatedAtEpochMs = record.requiredLong("updatedAtEpochMs"),
            terminalAtEpochMs = record.optionalLong("terminalAtEpochMs"),
            retryableErrorCode = record.optionalString("retryableErrorCode"),
            executionProfileKey = record.requiredString("executionProfileKey")
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.optionalString(key: String): String? = get(key)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull

    private fun JsonObject.optionalLong(key: String): Long? = get(key)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.long
}
