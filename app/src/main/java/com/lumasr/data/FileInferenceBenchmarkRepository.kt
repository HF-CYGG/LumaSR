package com.lumasr.data

import com.lumasr.domain.BenchmarkIdentity
import com.lumasr.domain.BenchmarkPublicationGate
import com.lumasr.domain.CalibratedCpuProfile
import com.lumasr.domain.CalibratedTtaProfile
import com.lumasr.domain.CalibratedVulkanProfile
import com.lumasr.domain.CalibratedVulkanTier
import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.Fp16QualityMetrics
import com.lumasr.domain.InferenceBenchmarkRepository
import com.lumasr.domain.InferenceBenchmarkResult
import com.lumasr.domain.ResolutionTier
import com.lumasr.domain.VulkanCalibrationDecision
import com.lumasr.domain.VulkanExecutionTuning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val atomicMove: (File, File) -> Unit = ::systemMoveReplacing
) : InferenceBenchmarkRepository {
    override suspend fun save(result: InferenceBenchmarkResult) {
        check(publish(result, BenchmarkPublicationGate())) {
            "Fresh benchmark publication was unexpectedly cancelled"
        }
    }

    override suspend fun publish(
        result: InferenceBenchmarkResult,
        gate: BenchmarkPublicationGate
    ): Boolean = withContext(ioDispatcher) {
        PROCESS_MUTEX.withLock {
            validateResult(result)
            ensureDirectory()
            val current = readAll()
            val existing = current.firstOrNull { it.identity == result.identity }
            if (existing != null &&
                result.measuredAtEpochMs in existing.measuredAtEpochMs..existing.validUntilEpochMs &&
                existing.calibrationMode.priority > result.calibrationMode.priority
            ) {
                return@withLock gate.tryCommit(commit = {})
            }
            val updated = current
                .filterNot { it.identity == result.identity }
                .plus(result)
                .sortedWith(RESULT_ORDER)
            require(updated.size <= MAX_RESULT_COUNT) { "Too many benchmark results" }
            val target = recordFile()
            val rollback = prepareRollbackCopy(target)
            val temporary = prepareAtomic(
                BenchmarkResultJson.encode(updated).toByteArray(Charsets.UTF_8)
            )
            try {
                gate.tryCommit(
                    commit = { atomicMove(temporary, target) },
                    rollback = { rollbackPublication(target, rollback) }
                )
            } finally {
                temporary.delete()
                rollback?.delete()
            }
        }
    }

    override suspend fun list(): List<InferenceBenchmarkResult> = withContext(ioDispatcher) {
        PROCESS_MUTEX.withLock { readAll() }
    }

    override suspend fun findValid(
        identity: BenchmarkIdentity,
        nowEpochMs: Long
    ): InferenceBenchmarkResult? = withContext(ioDispatcher) {
        require(nowEpochMs >= 0L) { "Current benchmark time must be non-negative" }
        PROCESS_MUTEX.withLock {
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
            val records = BenchmarkResultJson.decodeRecords(file.readText(Charsets.UTF_8))
            check(records.size <= MAX_RESULT_COUNT) { "Too many benchmark results" }
            val identities = mutableSetOf<BenchmarkIdentity>()
            var discardedRecord = false
            val validResults = records.mapNotNull { record ->
                try {
                    BenchmarkResultJson.decodeResult(record).also(::validateResult).takeIf { result ->
                        identities.add(result.identity).also { unique ->
                            if (!unique) discardedRecord = true
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    discardedRecord = true
                    null
                }
            }.sortedWith(RESULT_ORDER)
            if (discardedRecord) {
                if (validResults.isEmpty()) {
                    quarantine(file)
                } else {
                    rewriteRecoveredRecords(validResults)
                }
            }
            validResults
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            quarantine(file)
            emptyList()
        }
    }

    private fun rewriteRecoveredRecords(results: List<InferenceBenchmarkResult>) {
        val target = recordFile()
        val rollback = try {
            prepareRollbackCopy(target) ?: return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val temporary = try {
            prepareAtomic(BenchmarkResultJson.encode(results).toByteArray(Charsets.UTF_8))
        } catch (cancelled: CancellationException) {
            rollback.delete()
            throw cancelled
        } catch (_: Exception) {
            rollback.delete()
            return
        }
        try {
            atomicMove(temporary, target)
        } catch (cancelled: CancellationException) {
            restoreRecoveredAggregate(target, rollback, cancelled)
            throw cancelled
        } catch (moveError: Exception) {
            restoreRecoveredAggregate(target, rollback, moveError)
        } finally {
            temporary.delete()
            rollback.delete()
        }
    }

    private fun restoreRecoveredAggregate(
        target: File,
        rollback: File,
        moveError: Exception
    ) {
        try {
            rollbackPublication(target, rollback)
        } catch (restoreError: Exception) {
            moveError.addSuppressed(restoreError)
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
        result.ttaProfiles.forEach { profile ->
            validateQuality(profile.quality, "TTA")
        }
    }

    private fun validateQuality(quality: Fp16QualityMetrics, label: String) {
        require(quality.ssim.isFinite() && quality.ssim in -1.0..1.0) {
            "$label SSIM must be finite and in [-1, 1]"
        }
        require(
            (quality.psnrDb.isFinite() && quality.psnrDb >= 0.0) ||
                quality.psnrDb == Double.POSITIVE_INFINITY
        ) { "$label PSNR must be non-negative or positive infinity" }
        require(quality.p99ChannelError in 0..255) {
            "$label p99 channel error must be in [0, 255]"
        }
    }

    private val CalibrationMode.priority: Int
        get() = when (this) {
            CalibrationMode.LEGACY -> 0
            CalibrationMode.QUICK -> 1
            CalibrationMode.DEEP -> 2
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

    private fun prepareRollbackCopy(target: File): File? {
        if (!target.isFile) return null
        val rollback = File.createTempFile(target.name, ".$ROLLBACK_EXTENSION", directory)
        try {
            Files.copy(
                target.toPath(),
                rollback.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            FileOutputStream(rollback, true).use { stream -> stream.fd.sync() }
            return rollback
        } catch (error: Exception) {
            rollback.delete()
            throw error
        }
    }

    private fun rollbackPublication(target: File, rollback: File?) {
        if (rollback != null) {
            atomicMove(rollback, target)
        } else {
            Files.deleteIfExists(target.toPath())
        }
    }

    private fun quarantine(file: File) {
        try {
            val destination = File(
                directory,
                "${file.name}.${System.currentTimeMillis()}.$CORRUPT_EXTENSION"
            )
            atomicMove(file, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Invalid benchmark data remains ignored if the filesystem cannot quarantine it.
        }
    }

    private fun recordFile(): File = File(directory, RECORD_FILE_NAME)

    private companion object {
        val PROCESS_MUTEX = Mutex()
        const val RECORD_FILE_NAME = "inference-benchmarks.json"
        const val TEMP_EXTENSION = "tmp"
        const val ROLLBACK_EXTENSION = "rollback"
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

private fun systemMoveReplacing(source: File, destination: File) {
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

private object BenchmarkResultJson {
    private const val SCHEMA_VERSION = 6
    private val LEGACY_SCHEMA_VERSIONS = setOf(3, 4, 5)
    private const val POSITIVE_INFINITY = "+Infinity"
    private val json = Json

    fun encode(results: List<InferenceBenchmarkResult>): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("results", buildJsonArray { results.forEach { add(encodeResult(it)) } })
    }.toString()

    fun decodeRecords(value: String): List<Pair<Int, JsonElement>> {
        val root = json.parseToJsonElement(value).jsonObject
        val schemaVersion = root.requiredInt("schemaVersion")
        check(schemaVersion == SCHEMA_VERSION || schemaVersion in LEGACY_SCHEMA_VERSIONS) {
            "Unsupported benchmark schema"
        }
        return root.requiredArray("results").map { record -> schemaVersion to record }
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
        put("calibrationMode", result.calibrationMode.name)
        put("vulkanProfiles", buildJsonArray {
            result.vulkanProfiles.forEach { add(encodeVulkanProfile(it)) }
        })
        put("vulkanTiers", buildJsonArray {
            result.vulkanTiers.forEach { add(encodeVulkanTier(it)) }
        })
        put("cpuProfiles", buildJsonArray {
            result.cpuProfiles.forEach { add(encodeCpuProfile(it)) }
        })
        put("ttaProfiles", buildJsonArray {
            result.ttaProfiles.forEach { add(encodeTtaProfile(it)) }
        })
        put("measuredAtEpochMs", result.measuredAtEpochMs)
        put("validUntilEpochMs", result.validUntilEpochMs)
    }

    private fun encodeVulkanProfile(profile: CalibratedVulkanProfile): JsonObject = buildJsonObject {
        put("resolutionTier", profile.resolutionTier.name)
        put("tileSize", profile.tileSize)
        put("commandBatchSize", profile.commandBatchSize)
        put("maxPipelineDepth", profile.maxPipelineDepth)
        put("tuning", buildJsonObject {
            put("useSubgroupOps", profile.tuning.useSubgroupOps)
            put("useShaderLocalMemory", profile.tuning.useShaderLocalMemory)
            put("useWinogradConvolution", profile.tuning.useWinogradConvolution)
            put("useSgemmConvolution", profile.tuning.useSgemmConvolution)
        })
    }

    private fun encodeCpuProfile(profile: CalibratedCpuProfile): JsonObject = buildJsonObject {
        put("resolutionTier", profile.resolutionTier.name)
        put("tileSize", profile.tileSize)
        put("cpuThreads", profile.cpuThreads)
        put("openmpBlocktimeMs", profile.openmpBlocktimeMs)
        put("postprocessThreads", profile.postprocessThreads)
    }

    private fun encodeVulkanTier(tier: CalibratedVulkanTier): JsonObject = buildJsonObject {
        put("resolutionTier", tier.resolutionTier.name)
        put("decision", tier.decision.name)
        put("baselineDurationNanos", tier.baselineDurationNanos)
        put("bestCandidateDurationNanos", tier.bestCandidateDurationNanos)
        put("profile", tier.profile?.let(::encodeVulkanProfile) ?: JsonNull)
    }

    private fun encodeTtaProfile(profile: CalibratedTtaProfile): JsonObject = buildJsonObject {
        put("scale", profile.scale)
        put("noise", profile.noise)
        put("tileSize", profile.tileSize)
        put("passBatchSize", profile.passBatchSize)
        put("quality", buildJsonObject {
            put("ssim", profile.quality.ssim)
            put(
                "psnrDb",
                if (profile.quality.psnrDb == Double.POSITIVE_INFINITY) {
                    JsonPrimitive(POSITIVE_INFINITY)
                } else {
                    JsonPrimitive(profile.quality.psnrDb)
                }
            )
            put("p99ChannelError", profile.quality.p99ChannelError)
        })
        put("safeDurationNanos", profile.safeDurationNanos)
        put("optimizedDurationNanos", profile.optimizedDurationNanos)
    }

    fun decodeResult(encodedRecord: Pair<Int, JsonElement>): InferenceBenchmarkResult {
        val schemaVersion = encodedRecord.first
        val element = encodedRecord.second
        val record = element.jsonObject
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
            vulkanProfiles = record.requiredArray("vulkanProfiles").map {
                decodeVulkanProfile(it.jsonObject)
            },
            vulkanTiers = if (schemaVersion >= 5) {
                record.requiredArray("vulkanTiers").map { decodeVulkanTier(it.jsonObject) }
            } else {
                record.requiredArray("vulkanProfiles").map { profileElement ->
                    val profile = decodeVulkanProfile(profileElement.jsonObject)
                    CalibratedVulkanTier(
                        resolutionTier = profile.resolutionTier,
                        decision = VulkanCalibrationDecision.ENABLED,
                        profile = profile
                    )
                }
            },
            cpuProfiles = record.requiredArray("cpuProfiles").map {
                decodeCpuProfile(it.jsonObject)
            },
            ttaProfiles = if (schemaVersion == 3) {
                emptyList()
            } else {
                record.requiredArray("ttaProfiles").map { decodeTtaProfile(it.jsonObject) }
            },
            measuredAtEpochMs = record.requiredLong("measuredAtEpochMs"),
            validUntilEpochMs = record.requiredLong("validUntilEpochMs"),
            calibrationMode = if (schemaVersion >= SCHEMA_VERSION) {
                CalibrationMode.valueOf(record.requiredString("calibrationMode"))
            } else {
                CalibrationMode.LEGACY
            }
        )
    }

    private fun decodeVulkanProfile(record: JsonObject): CalibratedVulkanProfile =
        CalibratedVulkanProfile(
            resolutionTier = ResolutionTier.valueOf(record.requiredString("resolutionTier")),
            tileSize = record.requiredInt("tileSize"),
            commandBatchSize = record.requiredInt("commandBatchSize"),
            maxPipelineDepth = record.optionalInt("maxPipelineDepth") ?: 1,
            tuning = record["tuning"]?.jsonObject?.let { tuning ->
                VulkanExecutionTuning(
                    useSubgroupOps = tuning.requiredBoolean("useSubgroupOps"),
                    useShaderLocalMemory = tuning.requiredBoolean("useShaderLocalMemory"),
                    useWinogradConvolution = tuning.requiredBoolean("useWinogradConvolution"),
                    useSgemmConvolution = tuning.requiredBoolean("useSgemmConvolution")
                )
            } ?: VulkanExecutionTuning.DEFAULT
        )

    private fun decodeCpuProfile(record: JsonObject): CalibratedCpuProfile =
        CalibratedCpuProfile(
            resolutionTier = ResolutionTier.valueOf(record.requiredString("resolutionTier")),
            tileSize = record.requiredInt("tileSize"),
            cpuThreads = record.requiredInt("cpuThreads"),
            openmpBlocktimeMs = record.requiredInt("openmpBlocktimeMs"),
            postprocessThreads = record.requiredInt("postprocessThreads")
        )

    private fun decodeVulkanTier(record: JsonObject): CalibratedVulkanTier {
        val profile = record["profile"]?.takeUnless { it is JsonNull }?.jsonObject
        return CalibratedVulkanTier(
            resolutionTier = ResolutionTier.valueOf(record.requiredString("resolutionTier")),
            decision = VulkanCalibrationDecision.valueOf(record.requiredString("decision")),
            baselineDurationNanos = record.optionalLong("baselineDurationNanos"),
            bestCandidateDurationNanos = record.optionalLong("bestCandidateDurationNanos"),
            profile = profile?.let(::decodeVulkanProfile)
        )
    }

    private fun decodeTtaProfile(record: JsonObject): CalibratedTtaProfile {
        val quality = record.getValue("quality").jsonObject
        return CalibratedTtaProfile(
            scale = record.requiredInt("scale"),
            noise = record.requiredInt("noise"),
            tileSize = record.requiredInt("tileSize"),
            passBatchSize = record.requiredInt("passBatchSize"),
            quality = Fp16QualityMetrics(
                ssim = quality.requiredDouble("ssim"),
                psnrDb = quality.requiredPsnr("psnrDb"),
                p99ChannelError = quality.requiredInt("p99ChannelError")
            ),
            safeDurationNanos = record.requiredLong("safeDurationNanos"),
            optimizedDurationNanos = record.requiredLong("optimizedDurationNanos")
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive
        .also { check(it.isString) { "$key must be a JSON string" } }
        .content

    private fun JsonObject.requiredBoolean(key: String): Boolean = getValue(key).jsonPrimitive
        .also { check(!it.isString) { "$key must be a JSON boolean" } }
        .content.let { value ->
            check(value == "true" || value == "false") { "$key must be a JSON boolean" }
            value.toBooleanStrict()
        }

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
