package com.lumasr.domain

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object DeviceFingerprintHasher {
    fun sha256(rawFingerprint: String): String = sha256Hex(rawFingerprint)

    internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object BenchmarkIdentityFactory {
    const val SCHEMA_KEY = "inference-benchmark-v2"

    fun create(
        modelId: String,
        engine: SuperResEngine,
        modelArtifactHash: String,
        runtimeBuildKey: String,
        device: DeviceCapabilityProfile,
        rawDeviceFingerprint: String
    ): BenchmarkIdentity {
        require(modelId.isNotBlank()) { "Calibration model id must not be blank" }
        require(rawDeviceFingerprint.isNotBlank()) { "Device fingerprint must not be blank" }
        return BenchmarkIdentity(
            schemaKey = SCHEMA_KEY,
            engineKey = "ncnn:${engine.name}:${DeviceFingerprintHasher.sha256Hex(modelId)}",
            modelArtifactHash = modelArtifactHash,
            runtimeBuildKey = runtimeBuildKey,
            profileHash = device.profileKey,
            deviceFingerprint = DeviceFingerprintHasher.sha256(rawDeviceFingerprint),
            gpuDriverVersion = device.gpuDriverVersion
        )
    }
}

object ModelArtifactIdentityFactory {
    fun create(model: ModelPack): String = DeviceFingerprintHasher.sha256Hex(
        buildString {
            appendField(model.id)
            appendField(model.engine.name)
            appendField(model.modelDir)
            appendField(model.assetPath)
            appendField(model.modelFileBase.orEmpty())
            appendField(model.requiredFiles.sorted().joinToString("\u0000"))
            appendField(model.assetBytes?.toString().orEmpty())
            appendField(model.scales.sorted().joinToString(","))
            appendField(model.denoise.sorted().joinToString(","))
            appendField(model.defaultScale.toString())
            appendField(model.defaultNoise.toString())
            appendField(model.supportsTta.toString())
        }
    )

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}

object InferenceExecutionProfileMapper {
    fun apply(params: UpscaleParams, profile: InferenceExecutionProfile): UpscaleParams = params.copy(
        tileSize = profile.tileSize,
        gpuHeadroomPercent = profile.gpuHeadroomPercent,
        accelerationMode = profile.accelerationMode,
        tta = profile.tta,
        nativeBackend = profile.backend,
        inferencePrecision = profile.precision,
        cpuThreads = profile.cpuThreads,
        openmpBlocktimeMs = profile.openmpBlocktimeMs,
        postprocessThreads = profile.postprocessThreads,
        commandBatchSize = profile.commandBatchSize,
        modelCacheCapacity = profile.modelCacheCapacity
    )

    fun from(params: UpscaleParams): InferenceExecutionProfile = InferenceExecutionProfile(
        backend = params.nativeBackend,
        accelerationMode = params.accelerationMode,
        precision = params.inferencePrecision,
        cpuThreads = params.cpuThreads.coerceAtLeast(1),
        openmpBlocktimeMs = params.openmpBlocktimeMs.coerceIn(0, 100),
        postprocessThreads = params.postprocessThreads.coerceIn(1, 64),
        tileSize = UpscaleParamsFactory.sanitizeTileSize(params.tileSize),
        commandBatchSize = params.commandBatchSize.coerceIn(1, 8),
        gpuHeadroomPercent = UpscaleParamsFactory.sanitizeGpuHeadroomPercent(
            params.gpuHeadroomPercent
        ),
        modelCacheCapacity = params.modelCacheCapacity.coerceIn(1, 2),
        tta = params.tta
    )
}

fun interface ProcessingExecutionProfileResolver {
    suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long
    ): UpscaleParams

    suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long,
        outputPixels: Long?
    ): UpscaleParams = resolve(modelId, params, nowEpochMs)
}

class LazyProcessingExecutionProfileResolver(
    private val resolverFactory: () -> ProcessingExecutionProfileResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProcessingExecutionProfileResolver {
    private val resolverMutex = Mutex()
    private var resolver: ProcessingExecutionProfileResolver? = null

    override suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long
    ): UpscaleParams = withContext(ioDispatcher) {
        try {
            resolver().resolve(modelId, params, nowEpochMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failClosed(params)
        }
    }

    override suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long,
        outputPixels: Long?
    ): UpscaleParams = withContext(ioDispatcher) {
        try {
            resolver().resolve(modelId, params, nowEpochMs, outputPixels)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failClosed(params)
        }
    }

    private suspend fun resolver(): ProcessingExecutionProfileResolver = resolverMutex.withLock {
        resolver ?: resolverFactory().also { resolver = it }
    }

    private fun failClosed(params: UpscaleParams): UpscaleParams = params.copy(
        tileSize = UpscaleParamsFactory.sanitizeTileSize(params.tileSize),
        gpuHeadroomPercent = UpscaleParamsFactory.sanitizeGpuHeadroomPercent(
            params.gpuHeadroomPercent
        ),
        nativeBackend = NativeBackend.LEGACY_SAFE,
        inferencePrecision = InferencePrecision.FP32,
        cpuThreads = params.cpuThreads.coerceAtLeast(1),
        commandBatchSize = 1,
        modelCacheCapacity = 1,
        pipelinePasses = params.pipelinePasses.map(::failClosed)
    )
}

class InferenceExecutionProfileResolver(
    private val repository: InferenceBenchmarkRepository,
    private val device: DeviceCapabilityProfile,
    private val rawDeviceFingerprint: String,
    private val modelArtifactIdentities: Map<String, String>,
    private val runtimeBuildKey: String
) : ProcessingExecutionProfileResolver {
    override suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long
    ): UpscaleParams = resolve(modelId, params, nowEpochMs, outputPixels = null)

    override suspend fun resolve(
        modelId: String,
        params: UpscaleParams,
        nowEpochMs: Long,
        outputPixels: Long?
    ): UpscaleParams {
        require(nowEpochMs >= 0L) { "Profile resolution time must be non-negative" }
        if (params.pipelinePasses.isNotEmpty()) {
            val outer = selectAndApply(params.copy(pipelinePasses = emptyList()), benchmark = null)
            return outer.copy(
                pipelinePasses = params.pipelinePasses.map { pass ->
                    selectAndApply(pass.copy(pipelinePasses = emptyList()), benchmark = null)
                }
            )
        }

        val modelArtifactHash = modelArtifactIdentities[modelId]
            ?: return selectAndApply(params, benchmark = null)
        val identity = runCatching {
            BenchmarkIdentityFactory.create(
                modelId = modelId,
                engine = params.engine,
                modelArtifactHash = modelArtifactHash,
                runtimeBuildKey = runtimeBuildKey,
                device = device,
                rawDeviceFingerprint = rawDeviceFingerprint
            )
        }.getOrNull() ?: return selectAndApply(params, benchmark = null)
        val benchmark = repository.findValid(identity, nowEpochMs)
        return selectAndApply(params, benchmark, identity, nowEpochMs, outputPixels)
    }

    private fun selectAndApply(
        params: UpscaleParams,
        benchmark: InferenceBenchmarkResult?,
        identity: BenchmarkIdentity? = null,
        nowEpochMs: Long = 0L,
        outputPixels: Long? = null
    ): UpscaleParams {
        val resolutionTier = runCatching {
            ResolutionTier.fromOutputPixels(outputPixels)
        }.getOrNull()
        val calibratedVulkanProfile = resolutionTier?.let { tier ->
            benchmark?.vulkanProfiles?.singleOrNull { it.resolutionTier == tier }
        }
        val calibratedCpuProfile = resolutionTier?.let { tier ->
            benchmark?.cpuProfiles?.singleOrNull { it.resolutionTier == tier }
        }?.takeIf {
            benchmark != null &&
                identity != null &&
                AdaptiveInferencePolicy.isBenchmarkValid(benchmark.identity, identity) &&
                nowEpochMs in benchmark.measuredAtEpochMs..benchmark.validUntilEpochMs
        }
        val selectedProfile = AdaptiveInferencePolicy.selectExecutionProfile(
            device = device,
            accelerationMode = params.accelerationMode,
            ttaEnabled = params.tta,
            benchmarkResult = benchmark,
            currentBenchmarkIdentity = identity,
            currentSafeTileSize = params.tileSize,
            nowEpochMs = nowEpochMs,
            calibratedVulkanProfile = calibratedVulkanProfile
        )
        val profile = selectedProfile.copy(
            tileSize = if (params.accelerationMode == AccelerationMode.CPU) {
                calibratedCpuProfile?.tileSize ?: selectedProfile.tileSize
            } else {
                selectedProfile.tileSize
            },
            cpuThreads = calibratedCpuProfile?.cpuThreads ?: params.cpuThreads,
            openmpBlocktimeMs = calibratedCpuProfile?.openmpBlocktimeMs
                ?: params.openmpBlocktimeMs,
            postprocessThreads = calibratedCpuProfile?.postprocessThreads
                ?: params.postprocessThreads
        )
        return InferenceExecutionProfileMapper.apply(params, profile)
    }
}
