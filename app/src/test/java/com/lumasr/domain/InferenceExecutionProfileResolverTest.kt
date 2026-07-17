package com.lumasr.domain

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceExecutionProfileResolverTest {
    @Test
    fun mapperPersistsOpenmpAndPostprocessExecutionControls() {
        val base = params()
        val profile = InferenceExecutionProfileMapper.from(base).copy(
            openmpBlocktimeMs = 37,
            postprocessThreads = 3
        )

        val resolved = InferenceExecutionProfileMapper.apply(base, profile)

        assertEquals(37, resolved.openmpBlocktimeMs)
        assertEquals(3, resolved.postprocessThreads)
        assertEquals(37, InferenceExecutionProfileMapper.from(resolved).openmpBlocktimeMs)
        assertEquals(3, InferenceExecutionProfileMapper.from(resolved).postprocessThreads)
    }

    @Test
    fun legacyResolutionPreservesConfiguredCpuExecutionControls() = runBlocking {
        val base = params().copy(
            accelerationMode = AccelerationMode.CPU,
            openmpBlocktimeMs = 33,
            postprocessThreads = 4
        )
        val resolved = resolver(FakeBenchmarkRepository(null)).resolve(
            "model-a",
            base,
            nowEpochMs = 1_500L
        )

        assertEquals(33, resolved.openmpBlocktimeMs)
        assertEquals(4, resolved.postprocessThreads)
    }

    @Test
    fun lazyResolverDefersCapabilityProbeAndRunsResolutionOffCallerThread() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "calibration-io")
        }.asCoroutineDispatcher()
        var factoryInvoked = false
        var resolutionThread = ""
        val lazy = LazyProcessingExecutionProfileResolver(
            resolverFactory = {
                factoryInvoked = true
                ProcessingExecutionProfileResolver { _, params, _ ->
                    resolutionThread = Thread.currentThread().name
                    params
                }
            },
            ioDispatcher = dispatcher
        )
        try {
            assertFalse(factoryInvoked)

            lazy.resolve("model-a", params(), 1_500L)

            assertTrue(factoryInvoked)
            assertTrue(resolutionThread.contains("calibration-io"))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun lazyResolverFailsClosedToLegacyForProbeOrRepositoryExceptions() = runBlocking {
        val pipeline = params().copy(
            pipelinePasses = listOf(params().copy(scale = 1), params())
        )
        val lazy = LazyProcessingExecutionProfileResolver(
            resolverFactory = { throw IllegalStateException("capability probe failed") },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val resolved = lazy.resolve("model-a", pipeline, 1_500L)

        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        assertEquals(1, resolved.commandBatchSize)
        assertEquals(1, resolved.modelCacheCapacity)
        assertTrue(resolved.pipelinePasses.all { it.nativeBackend == NativeBackend.LEGACY_SAFE })
        assertTrue(resolved.pipelinePasses.all { it.inferencePrecision == InferencePrecision.FP32 })
    }

    @Test
    fun buildsCompletePrivacySafeIdentityFromModelDeviceProfileAndDriver() {
        val artifactHash = ModelArtifactIdentityFactory.create(model())
        val identity = BenchmarkIdentityFactory.create(
            modelId = model().id,
            engine = model().engine,
            modelArtifactHash = artifactHash,
            runtimeBuildKey = "app-1:release:arm64-v8a",
            device = device(),
            rawDeviceFingerprint = "Xiaomi/aurora/user/release-keys"
        )

        assertEquals(BenchmarkIdentityFactory.SCHEMA_KEY, identity.schemaKey)
        assertTrue(identity.engineKey.startsWith("ncnn:WAIFU2X:"))
        assertEquals(device().profileKey, identity.profileHash)
        assertTrue(identity.deviceFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertFalse(identity.deviceFingerprint.contains("Xiaomi"))
        assertEquals("driver-v1", identity.gpuDriverVersion)
        assertEquals(artifactHash, identity.modelArtifactHash)
        assertEquals("app-1:release:arm64-v8a", identity.runtimeBuildKey)
        assertNotEquals(
            identity,
            BenchmarkIdentityFactory.create(
                modelId = model().id,
                engine = model().engine,
                modelArtifactHash = ModelArtifactIdentityFactory.create(
                    model().copy(assetBytes = model().assetBytes?.plus(1L))
                ),
                runtimeBuildKey = "app-1:release:arm64-v8a",
                device = device(),
                rawDeviceFingerprint = "Xiaomi/aurora/user/release-keys"
            )
        )
        assertNotEquals(
            identity,
            identity.copy(runtimeBuildKey = "app-2:release:arm64-v8a")
        )
    }

    @Test
    fun mapsEveryQualifiedExecutionFieldIntoPersistableParams() = runBlocking {
        val base = params()
        val identity = identity(base)
        val repository = FakeBenchmarkRepository(benchmark(identity, batchSize = 8))
        val resolver = resolver(repository = repository)

        val resolved = resolver.resolve("model-a", base, nowEpochMs = 1_500L)

        assertEquals(NativeBackend.OPTIMIZED, resolved.nativeBackend)
        assertEquals(AccelerationMode.VULKAN, resolved.accelerationMode)
        assertEquals(InferencePrecision.FP16, resolved.inferencePrecision)
        assertEquals(4, resolved.cpuThreads)
        assertEquals(512, resolved.tileSize)
        assertEquals(8, resolved.commandBatchSize)
        assertEquals(8, resolved.gpuHeadroomPercent)
        assertEquals(2, resolved.modelCacheCapacity)
        assertFalse(resolved.tta)
    }

    @Test
    fun resolvesSmallStandardAndLargeVulkanProfilesFromOutputPixels() = runBlocking {
        val base = params()
        val resolver = resolver(
            repository = FakeBenchmarkRepository(
                benchmark(identity(base), profiles = resolutionProfiles())
            )
        )

        val small = resolver.resolve("model-a", base, 1_500L, outputPixels = 7_999_999L)
        val standardAtLowerBound = resolver.resolve(
            "model-a",
            base,
            1_500L,
            outputPixels = 8_000_000L
        )
        val standardAtUpperBound = resolver.resolve(
            "model-a",
            base,
            1_500L,
            outputPixels = 24_000_000L
        )
        val large = resolver.resolve("model-a", base, 1_500L, outputPixels = 24_000_001L)
        val missingPixels = resolver.resolve("model-a", base, 1_500L, outputPixels = null)

        assertEquals(768 to 8, small.tileSize to small.commandBatchSize)
        assertEquals(256 to 4, standardAtLowerBound.tileSize to standardAtLowerBound.commandBatchSize)
        assertEquals(256 to 4, standardAtUpperBound.tileSize to standardAtUpperBound.commandBatchSize)
        assertEquals(128 to 2, large.tileSize to large.commandBatchSize)
        assertEquals(256 to 4, missingPixels.tileSize to missingPixels.commandBatchSize)
    }

    @Test
    fun missingTierOrInvalidOutputPixelsFallsBackToLegacyRecommendedFields() = runBlocking {
        val base = params()
        val benchmark = benchmark(
            identity = identity(base),
            batchSize = 8,
            profiles = listOf(CalibratedVulkanProfile(ResolutionTier.SMALL, 768, 2))
        )
        val resolver = resolver(FakeBenchmarkRepository(benchmark))

        val missingTier = resolver.resolve(
            "model-a",
            base,
            1_500L,
            outputPixels = 24_000_001L
        )
        val invalidPixels = resolver.resolve("model-a", base, 1_500L, outputPixels = -1L)

        assertEquals(512 to 8, missingTier.tileSize to missingTier.commandBatchSize)
        assertEquals(512 to 8, invalidPixels.tileSize to invalidPixels.commandBatchSize)
    }

    @Test
    fun missingStaleLowRamUnsupportedAndExplicitCpuStayOnLegacyFp32() = runBlocking {
        val base = params()
        val currentIdentity = identity(base)
        val stale = benchmark(currentIdentity, validUntil = 1_499L)
        val cases = listOf(
            resolver(repository = FakeBenchmarkRepository(null)) to base,
            resolver(repository = FakeBenchmarkRepository(stale)) to base,
            resolver(
                repository = FakeBenchmarkRepository(benchmark(currentIdentity)),
                device = device().copy(isLowRamDevice = true)
            ) to base,
            resolver(
                repository = FakeBenchmarkRepository(benchmark(currentIdentity)),
                device = device().copy(supportsVulkan = false)
            ) to base,
            resolver(repository = FakeBenchmarkRepository(benchmark(currentIdentity))) to
                base.copy(accelerationMode = AccelerationMode.CPU)
        )

        cases.forEach { (resolver, input) ->
            val resolved = resolver.resolve("model-a", input, nowEpochMs = 1_500L)
            assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
            assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
            assertEquals(1, resolved.commandBatchSize)
            assertEquals(1, resolved.modelCacheCapacity)
        }
        assertEquals(
            AccelerationMode.CPU,
            cases.last().first.resolve("model-a", cases.last().second, 1_500L).accelerationMode
        )
    }

    @Test
    fun ttaKeepsCalibratedBackendButTightensAllPersistedFields() = runBlocking {
        val base = params().copy(tta = true)
        val resolver = resolver(
            repository = FakeBenchmarkRepository(benchmark(identity(base), batchSize = 8))
        )

        val resolved = resolver.resolve("model-a", base, nowEpochMs = 1_500L)

        assertEquals(NativeBackend.OPTIMIZED, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP16, resolved.inferencePrecision)
        assertTrue(resolved.tta)
        assertEquals(256, resolved.tileSize)
        assertEquals(1, resolved.commandBatchSize)
        assertTrue(resolved.gpuHeadroomPercent >= 10)
    }

    @Test
    fun multiPassPlanRemainsLegacyUntilEveryModelCanBeIdentifiedAndCalibrated() = runBlocking {
        val pass = params().copy(taskId = "task", modelName = "denoiser", scale = 1)
        val pipeline = params().copy(taskId = "task", pipelinePasses = listOf(pass, params()))
        val resolver = resolver(
            repository = FakeBenchmarkRepository(benchmark(identity(pipeline), batchSize = 8))
        )

        val resolved = resolver.resolve("model-a", pipeline, nowEpochMs = 1_500L)

        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        assertTrue(resolved.pipelinePasses.isNotEmpty())
        assertTrue(resolved.pipelinePasses.all { it.nativeBackend == NativeBackend.LEGACY_SAFE })
        assertTrue(resolved.pipelinePasses.all { it.inferencePrecision == InferencePrecision.FP32 })
    }

    private fun resolver(
        repository: InferenceBenchmarkRepository,
        device: DeviceCapabilityProfile = device()
    ) = InferenceExecutionProfileResolver(
        repository = repository,
        device = device,
        rawDeviceFingerprint = "Xiaomi/aurora/user/release-keys",
        modelArtifactIdentities = mapOf("model-a" to ModelArtifactIdentityFactory.create(model())),
        runtimeBuildKey = "app-1:test:arm64-v8a"
    )

    private fun identity(params: UpscaleParams) = BenchmarkIdentityFactory.create(
        modelId = "model-a",
        engine = params.engine,
        modelArtifactHash = ModelArtifactIdentityFactory.create(model()),
        runtimeBuildKey = "app-1:test:arm64-v8a",
        device = device(),
        rawDeviceFingerprint = "Xiaomi/aurora/user/release-keys"
    )

    private fun model() = ModelPack(
        id = "model-a",
        displayName = "Model A",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models/model-a",
        assetPath = "models/model-a.zip",
        modelFileBase = null,
        isBuiltIn = true,
        requiredFiles = listOf("noise0_scale2.0x_model.bin", "noise0_scale2.0x_model.param"),
        assetBytes = 1_024L,
        description = "test",
        scenes = listOf("anime"),
        scales = listOf(2),
        denoise = listOf(0),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "fast",
        qualityLevel = "high"
    )

    private fun benchmark(
        identity: BenchmarkIdentity,
        batchSize: Int = 4,
        validUntil: Long = 2_000L,
        profiles: List<CalibratedVulkanProfile> = emptyList()
    ) = InferenceBenchmarkResult(
        identity = identity,
        fp16Quality = Fp16QualityMetrics(0.999, 48.0, 1),
        recommendedCommandBatchSize = batchSize,
        measuredAtEpochMs = 1_000L,
        validUntilEpochMs = validUntil,
        vulkanProfiles = profiles
    )

    private fun resolutionProfiles() = listOf(
        CalibratedVulkanProfile(ResolutionTier.SMALL, tileSize = 768, commandBatchSize = 8),
        CalibratedVulkanProfile(ResolutionTier.STANDARD, tileSize = 256, commandBatchSize = 4),
        CalibratedVulkanProfile(ResolutionTier.LARGE, tileSize = 128, commandBatchSize = 2)
    )

    private fun device() = DeviceCapabilityProfile(
        schemaKey = "device-capability-v1",
        profileKey = "device-capability-v1:test-profile",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 12_288,
        physicalCoreCount = 8,
        bigCoreCount = 4,
        littleCoreCount = 4,
        gpuVendor = "vendor",
        gpuDevice = "gpu",
        gpuDriverVersion = "driver-v1",
        gpuRoughScore = 800,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true
    )

    private fun params() = UpscaleParams(
        taskId = "task",
        inputPath = File("build/resolver/input.png").absolutePath,
        outputPath = File("build/resolver/output.png").absolutePath,
        engine = SuperResEngine.WAIFU2X,
        modelDir = File("build/resolver/model").absolutePath,
        modelName = "model",
        scale = 2,
        noise = 0,
        tileSize = 512,
        gpuHeadroomPercent = 8,
        accelerationMode = AccelerationMode.VULKAN,
        tta = false,
        outputFormat = OutputFormat.PNG
    )

    private class FakeBenchmarkRepository(
        private val value: InferenceBenchmarkResult?
    ) : InferenceBenchmarkRepository {
        override suspend fun save(result: InferenceBenchmarkResult) = Unit

        override suspend fun publish(
            result: InferenceBenchmarkResult,
            gate: BenchmarkPublicationGate
        ): Boolean = gate.tryCommit {}

        override suspend fun list(): List<InferenceBenchmarkResult> = listOfNotNull(value)

        override suspend fun findValid(
            identity: BenchmarkIdentity,
            nowEpochMs: Long
        ): InferenceBenchmarkResult? = value?.takeIf {
            it.identity == identity && nowEpochMs in it.measuredAtEpochMs..it.validUntilEpochMs
        }
    }
}
