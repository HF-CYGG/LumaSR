package com.lumasr.data

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.InferenceRuntimeBackend
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.QnnExecutionConfig
import com.lumasr.domain.QnnPerformanceMode
import com.lumasr.domain.QnnPrecision
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class FileProcessingWorkSpecRepository(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProcessingWorkSpecRepository {
    private val mutex = Mutex()

    override suspend fun save(workSpec: ProcessingWorkSpec) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDirectory()
            writeAtomic(
                target = workFile(workSpec.taskId),
                bytes = ProcessingWorkSpecJson.encode(workSpec).toByteArray(Charsets.UTF_8)
            )
        }
    }

    override suspend fun find(taskId: String): ProcessingWorkSpec? = withContext(ioDispatcher) {
        mutex.withLock {
            readRecord(workFile(taskId), expectedTaskId = taskId)
        }
    }

    override suspend fun delete(taskId: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val file = workFile(taskId)
            !file.exists() || file.delete()
        }
    }

    private fun readRecord(file: File, expectedTaskId: String): ProcessingWorkSpec? {
        if (!file.isFile) return null
        return try {
            ProcessingWorkSpecJson.decode(file.readText(Charsets.UTF_8)).also { workSpec ->
                check(workSpec.taskId == expectedTaskId) { "Task id does not match work sidecar" }
            }
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

    private fun workFile(taskId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(taskId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.$WORK_EXTENSION")
    }

    private fun quarantine(file: File) {
        try {
            val destination = File(directory, "${file.name}.${System.currentTimeMillis()}.$CORRUPT_EXTENSION")
            moveReplacing(file, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A damaged sidecar remains ignored if the filesystem cannot quarantine it.
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
        const val WORK_EXTENSION = "work"
        const val CORRUPT_EXTENSION = "corrupt"
        const val TEMP_EXTENSION = "tmp"
    }
}

private object ProcessingWorkSpecJson {
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1
    private val json = Json

    fun encode(workSpec: ProcessingWorkSpec): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("modelId", workSpec.modelId)
        put("outputPixels", workSpec.outputPixels)
        put("params", encodeParams(workSpec.params))
    }.toString()

    fun decode(value: String): ProcessingWorkSpec {
        val record = json.parseToJsonElement(value).jsonObject
        val schemaVersion = record.requiredInt("schemaVersion")
        check(schemaVersion == SCHEMA_VERSION || schemaVersion == LEGACY_SCHEMA_VERSION) {
            "Unsupported work spec schema"
        }
        return ProcessingWorkSpec.fromParams(
            modelId = record.requiredString("modelId"),
            params = decodeParams(record.getValue("params").jsonObject, schemaVersion),
            outputPixels = record.optionalLong("outputPixels")
        )
    }

    private fun encodeParams(params: UpscaleParams): JsonObject = buildJsonObject {
        put("taskId", params.taskId)
        put("inputPath", params.inputPath)
        put("outputPath", params.outputPath)
        put("engine", params.engine.name)
        put("modelDir", params.modelDir)
        put("modelName", params.modelName)
        put("modelFileBase", params.modelFileBase)
        put("modelScales", buildJsonArray { params.modelScales.forEach(::add) })
        put("scale", params.scale)
        put("noise", params.noise)
        put("tileSize", params.tileSize)
        put("gpuHeadroomPercent", params.gpuHeadroomPercent)
        put("accelerationMode", params.accelerationMode.name)
        put("tta", params.tta)
        put("outputFormat", params.outputFormat.name)
        put("exportMode", params.exportMode.name)
        put("outputMode", params.outputMode.name)
        put("outputCropLeft", params.outputCropLeft)
        put("outputCropTop", params.outputCropTop)
        put("outputCropWidth", params.outputCropWidth)
        put("outputCropHeight", params.outputCropHeight)
        put("retryCount", params.retryCount)
        put("regionIndex", params.regionIndex)
        put("pipelinePasses", buildJsonArray { params.pipelinePasses.forEach { add(encodeParams(it)) } })
        put("pipelineLabel", params.pipelineLabel)
        put("nativeBackend", params.nativeBackend.name)
        put("inferencePrecision", params.inferencePrecision.name)
        put("cpuThreads", params.cpuThreads)
        put("openmpBlocktimeMs", params.openmpBlocktimeMs)
        put("postprocessThreads", params.postprocessThreads)
        put("commandBatchSize", params.commandBatchSize)
        put("modelCacheCapacity", params.modelCacheCapacity)
        put("maxPipelineDepth", params.maxPipelineDepth)
        put("runtimeBackend", params.runtimeBackend.name)
        put("qnnExecutionConfig", params.qnnExecutionConfig?.let(::encodeQnnConfig) ?: JsonNull)
    }

    private fun encodeQnnConfig(config: QnnExecutionConfig): JsonObject = buildJsonObject {
        put("graphKey", config.graphKey)
        put("precision", config.precision.name)
        put("tileSize", config.tileSize)
        put("maxPipelineDepth", config.maxPipelineDepth)
        put("performanceMode", config.performanceMode.name)
    }

    private fun decodeParams(record: JsonObject, schemaVersion: Int): UpscaleParams = UpscaleParams(
        taskId = record.requiredString("taskId"),
        inputPath = record.requiredString("inputPath"),
        outputPath = record.requiredString("outputPath"),
        engine = SuperResEngine.valueOf(record.requiredString("engine")),
        modelDir = record.requiredString("modelDir"),
        modelName = record.requiredString("modelName"),
        modelFileBase = record.optionalString("modelFileBase"),
        modelScales = record.requiredArray("modelScales").map { it.jsonPrimitive.int },
        scale = record.requiredInt("scale"),
        noise = record.requiredInt("noise"),
        tileSize = record.requiredInt("tileSize"),
        gpuHeadroomPercent = record.requiredInt("gpuHeadroomPercent"),
        accelerationMode = AccelerationMode.valueOf(record.requiredString("accelerationMode")),
        tta = record.requiredBoolean("tta"),
        outputFormat = OutputFormat.valueOf(record.requiredString("outputFormat")),
        exportMode = ExportMode.valueOf(record.requiredString("exportMode")),
        outputMode = NativeOutputMode.valueOf(record.requiredString("outputMode")),
        outputCropLeft = record.requiredInt("outputCropLeft"),
        outputCropTop = record.requiredInt("outputCropTop"),
        outputCropWidth = record.requiredInt("outputCropWidth"),
        outputCropHeight = record.requiredInt("outputCropHeight"),
        retryCount = record.requiredInt("retryCount"),
        regionIndex = record.requiredInt("regionIndex"),
        pipelinePasses = record.requiredArray("pipelinePasses").map {
            decodeParams(it.jsonObject, schemaVersion)
        },
        pipelineLabel = record.optionalString("pipelineLabel"),
        nativeBackend = NativeBackend.valueOf(record.requiredString("nativeBackend")),
        inferencePrecision = InferencePrecision.valueOf(record.requiredString("inferencePrecision")),
        cpuThreads = record.requiredInt("cpuThreads"),
        openmpBlocktimeMs = record.requiredInt("openmpBlocktimeMs"),
        postprocessThreads = record.optionalInt("postprocessThreads") ?: 1,
        commandBatchSize = record.requiredInt("commandBatchSize"),
        modelCacheCapacity = record.requiredInt("modelCacheCapacity"),
        maxPipelineDepth = record.optionalInt("maxPipelineDepth") ?: 1,
        runtimeBackend = if (schemaVersion >= SCHEMA_VERSION) {
            InferenceRuntimeBackend.valueOf(record.requiredString("runtimeBackend"))
        } else {
            InferenceRuntimeBackend.NCNN
        },
        qnnExecutionConfig = if (schemaVersion >= SCHEMA_VERSION) {
            record["qnnExecutionConfig"]
                ?.takeUnless { it is JsonNull }
                ?.jsonObject
                ?.let(::decodeQnnConfig)
        } else {
            null
        }
    )

    private fun decodeQnnConfig(record: JsonObject): QnnExecutionConfig = QnnExecutionConfig(
        graphKey = record.requiredString("graphKey"),
        precision = QnnPrecision.valueOf(record.requiredString("precision")),
        tileSize = record.requiredInt("tileSize"),
        maxPipelineDepth = record.requiredInt("maxPipelineDepth"),
        performanceMode = QnnPerformanceMode.valueOf(record.requiredString("performanceMode"))
    )

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.requiredBoolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray

    private fun JsonObject.optionalString(key: String): String? = get(key)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull

    private fun JsonObject.optionalLong(key: String): Long? {
        val value = get(key) ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive
            .also { check(!it.isString) { "$key must be a JSON number or null" } }
            .long
    }

    private fun JsonObject.optionalInt(key: String): Int? {
        val value = get(key) ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive
            .also { check(!it.isString) { "$key must be a JSON number or null" } }
            .int
    }
}
