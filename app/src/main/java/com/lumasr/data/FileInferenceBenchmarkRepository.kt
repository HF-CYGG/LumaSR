package com.lumasr.data

import com.lumasr.domain.BenchmarkIdentity
import com.lumasr.domain.BenchmarkPublicationGate
import com.lumasr.domain.Fp16QualityMetrics
import com.lumasr.domain.InferenceBenchmarkRepository
import com.lumasr.domain.InferenceBenchmarkResult
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
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

class FileInferenceBenchmarkRepository(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : InferenceBenchmarkRepository {
    private val mutex = Mutex()

    override suspend fun save(result: InferenceBenchmarkResult) {
        check(publish(result, BenchmarkPublicationGate())) {
            "Fresh benchmark publication was unexpectedly cancelled"
        }
    }

    override suspend fun publish(
        result: InferenceBenchmarkResult,
        gate: BenchmarkPublicationGate
    ): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            validateResult(result)
            ensureDirectory()
            val updated = readAll()
                .filterNot { it.identity == result.identity }
                .plus(result)
                .sortedWith(RESULT_ORDER)
            require(updated.size <= MAX_RESULT_COUNT) { "Too many benchmark results" }
            prepareAtomic(BenchmarkResultJson.encode(updated).toByteArray(Charsets.UTF_8)).let {
                temporary ->
                try {
                    gate.tryCommit { moveReplacing(temporary, recordFile()) }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    override suspend fun list(): List<InferenceBenchmarkResult> = withContext(ioDispatcher) {
        mutex.withLock { readAll() }
    }

    override suspend fun findValid(
        identity: BenchmarkIdentity,
        nowEpochMs: Long
    ): InferenceBenchmarkResult? = withContext(ioDispatcher) {
        require(nowEpochMs >= 0L) { "Current benchmark time must be non-negative" }
        mutex.withLock {
            readAll().firstOrNull { result ->
                result.identity == identity &&
                    nowEpochMs >= result.measuredAtEpochMs &&
                    nowEpochMs <= result.validUntilEpochMs
            }
        }
    }

    private fun readAll(): List<InferenceBenchmarkResult> {
        val file = recordFile()
        if (!file.isFile) return emptyList()
        return try {
            check(file.length() in 0..MAX_RECORD_BYTES) { "Benchmark record is too large" }
            BenchmarkResultJson.decode(file.readText(Charsets.UTF_8)).also { results ->
                check(results.size <= MAX_RESULT_COUNT) { "Too many benchmark results" }
                check(results.map(InferenceBenchmarkResult::identity).distinct().size == results.size) {
                    "Duplicate benchmark identity"
                }
                results.forEach(::validateResult)
            }.sortedWith(RESULT_ORDER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            quarantine(file)
            emptyList()
        }
    }

    private fun validateResult(result: InferenceBenchmarkResult) {
        val quality = result.fp16Quality
        require(quality.ssim.isFinite() && quality.ssim in -1.0..1.0) {
            "Benchmark SSIM must be finite and in [-1, 1]"
        }
        require(
            (quality.psnrDb.isFinite() && quality.psnrDb >= 0.0) ||
                quality.psnrDb == Double.POSITIVE_INFINITY
        ) { "Benchmark PSNR must be non-negative or positive infinity" }
        require(quality.p99ChannelError in 0..255) {
            "Benchmark p99 channel error must be in [0, 255]"
        }
        require(result.recommendedCommandBatchSize in SUPPORTED_BATCH_SIZES) {
            "Benchmark command batch size is unsupported"
        }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create benchmark repository directory" }
    }

    private fun prepareAtomic(bytes: ByteArray): File {
        require(bytes.size <= MAX_RECORD_BYTES) { "Benchmark record is too large" }
        val target = recordFile()
        val temporary = File.createTempFile(target.name, ".$TEMP_EXTENSION", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            return temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun quarantine(file: File) {
        try {
            val destination = File(
                directory,
                "${file.name}.${System.currentTimeMillis()}.$CORRUPT_EXTENSION"
            )
            moveReplacing(file, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Invalid benchmark data remains ignored if the filesystem cannot quarantine it.
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

    private fun recordFile(): File = File(directory, RECORD_FILE_NAME)

    private companion object {
        const val RECORD_FILE_NAME = "inference-benchmarks.json"
        const val TEMP_EXTENSION = "tmp"
        const val CORRUPT_EXTENSION = "corrupt"
        const val MAX_RECORD_BYTES = 1_048_576L
        const val MAX_RESULT_COUNT = 128
        val SUPPORTED_BATCH_SIZES = setOf(1, 2, 4, 8)
        val RESULT_ORDER = compareBy<InferenceBenchmarkResult>(
            { it.identity.schemaKey },
            { it.identity.engineKey },
            { it.identity.modelArtifactHash },
            { it.identity.runtimeBuildKey },
            { it.identity.profileHash },
            { it.identity.deviceFingerprint },
            { it.identity.gpuDriverVersion.orEmpty() }
        )
    }
}

private object BenchmarkResultJson {
    private const val SCHEMA_VERSION = 2
    private const val POSITIVE_INFINITY = "+Infinity"
    private val json = Json

    fun encode(results: List<InferenceBenchmarkResult>): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("results", buildJsonArray { results.forEach { add(encodeResult(it)) } })
    }.toString()

    fun decode(value: String): List<InferenceBenchmarkResult> {
        val root = json.parseToJsonElement(value).jsonObject
        check(root.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "Unsupported benchmark schema"
        }
        return root.requiredArray("results").map { decodeResult(it.jsonObject) }
    }

    private fun encodeResult(result: InferenceBenchmarkResult): JsonObject = buildJsonObject {
        put("identity", buildJsonObject {
            put("schemaKey", result.identity.schemaKey)
            put("engineKey", result.identity.engineKey)
            put("modelArtifactHash", result.identity.modelArtifactHash)
            put("runtimeBuildKey", result.identity.runtimeBuildKey)
            put("profileHash", result.identity.profileHash)
            put("deviceFingerprint", result.identity.deviceFingerprint)
            put("gpuDriverVersion", result.identity.gpuDriverVersion)
        })
        put("quality", buildJsonObject {
            put("ssim", result.fp16Quality.ssim)
            put(
                "psnrDb",
                if (result.fp16Quality.psnrDb == Double.POSITIVE_INFINITY) {
                    JsonPrimitive(POSITIVE_INFINITY)
                } else {
                    JsonPrimitive(result.fp16Quality.psnrDb)
                }
            )
            put("p99ChannelError", result.fp16Quality.p99ChannelError)
        })
        put("recommendedCommandBatchSize", result.recommendedCommandBatchSize)
        put("measuredAtEpochMs", result.measuredAtEpochMs)
        put("validUntilEpochMs", result.validUntilEpochMs)
    }

    private fun decodeResult(record: JsonObject): InferenceBenchmarkResult {
        val identity = record.getValue("identity").jsonObject
        val quality = record.getValue("quality").jsonObject
        return InferenceBenchmarkResult(
            identity = BenchmarkIdentity(
                schemaKey = identity.requiredString("schemaKey"),
                engineKey = identity.requiredString("engineKey"),
                modelArtifactHash = identity.requiredString("modelArtifactHash"),
                runtimeBuildKey = identity.requiredString("runtimeBuildKey"),
                profileHash = identity.requiredString("profileHash"),
                deviceFingerprint = identity.requiredString("deviceFingerprint"),
                gpuDriverVersion = identity.optionalString("gpuDriverVersion")
            ),
            fp16Quality = Fp16QualityMetrics(
                ssim = quality.requiredDouble("ssim"),
                psnrDb = quality.requiredPsnr("psnrDb"),
                p99ChannelError = quality.requiredInt("p99ChannelError")
            ),
            recommendedCommandBatchSize = record.requiredInt("recommendedCommandBatchSize"),
            measuredAtEpochMs = record.requiredLong("measuredAtEpochMs"),
            validUntilEpochMs = record.requiredLong("validUntilEpochMs")
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive
        .also { check(it.isString) { "$key must be a JSON string" } }
        .content

    private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive
        .also { check(!it.isString) { "$key must be a JSON number" } }
        .int

    private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive
        .also { check(!it.isString) { "$key must be a JSON number" } }
        .long

    private fun JsonObject.requiredDouble(key: String): Double = getValue(key).jsonPrimitive
        .also { check(!it.isString) { "$key must be a JSON number" } }
        .double
        .also { check(it.isFinite()) { "$key must be finite" } }

    private fun JsonObject.requiredPsnr(key: String): Double {
        val primitive = getValue(key).jsonPrimitive
        return if (primitive.isString) {
            check(primitive.content == POSITIVE_INFINITY) { "$key has an unsupported string value" }
            Double.POSITIVE_INFINITY
        } else {
            primitive.double.also { check(it.isFinite()) { "$key must be finite" } }
        }
    }

    private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive
            .also { check(it.isString) { "$key must be a JSON string or null" } }
            .contentOrNull
    }
}
