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
            tta = true,
            pipelinePasses = listOf(
                params().copy(scale = 1, tileSize = 256, tta = true),
                params().copy(tileSize = 64, tta = true)
            )
        )
        val resolvers = listOf(
            LazyProcessingExecutionProfileResolver(
                resolverFactory = { throw IllegalStateException("capability probe failed") },
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            LazyProcessingExecutionProfileResolver(
                resolverFactory = {
                    ProcessingExecutionProfileResolver { _, _, _ ->
                        throw IllegalStateException("resolver failed")
                    }
                },
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )
        )

        resolvers.forEach { lazy ->
            val resolved = lazy.resolve("model-a", pipeline, 1_500L)

            assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
            assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
            assertEquals(256, resolved.tileSize)
            assertEquals(1, resolved.commandBatchSize)
            assertEquals(1, resolved.modelCacheCapacity)
            assertEquals(listOf(128, 64), resolved.pipelinePasses.map(UpscaleParams::tileSize))
            assertTrue(resolved.pipelinePasses.all { it.nativeBackend == NativeBackend.LEGACY_SAFE })
            assertTrue(resolved.pipelinePasses.all { it.inferencePrecision == InferencePrecision.FP32 })
        }
    }

    @Test
    fun lazyResolverAppliesTtaTileFallbackWhenRepositoryThrows() = runBlocking {
        val base = params().copy(tta = true)
        val repository = object : InferenceBenchmarkRepository {
            override suspend fun save(result: InferenceBenchmarkResult) = Unit

            override suspend fun publish(
                result: InferenceBenchmarkResult,
                gate: BenchmarkPublicationGate
            ): Boolean = false

            override suspend fun list(): List<InferenceBenchmarkResult> = emptyList()

            override suspend fun findValid(
                identity: BenchmarkIdentity,
                nowEpochMs: Long
            ): InferenceBenchmarkResult? = throw IllegalStateException("repository failed")
        }
        val lazy = LazyProcessingExecutionProfileResolver(
            resolverFactory = { resolver(repository) },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val resolved = lazy.resolve("model-a", base, 1_500L)

        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        assertEquals(256, resolved.tileSize)
        assertEquals(1, resolved.commandBatchSize)
        assertEquals(1, resolved.modelCacheCapacity)
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
        val repository = FakeBenchmarkRepository(
            benchmark(
                identity,
                batchSize = 8,
                profiles = listOf(
                    CalibratedVulkanProfile(ResolutionTier.STANDARD, 512, 8)
                ),
                cpuProfiles = cpuResolutionProfiles()
            )
        )
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
    fun explicitCpuAppliesEveryTieredCpuExecutionControl() = runBlocking {
        val base = params().copy(
            accelerationMode = AccelerationMode.CPU,
            cpuThreads = 3,
            openmpBlocktimeMs = 33,
            postprocessThreads = 3
        )
        val resolver = resolver(
            repository = FakeBenchmarkRepository(
                benchmark(identity(base), cpuProfiles = cpuResolutionProfiles())
            )
        )

        val small = resolver.resolve("model-a", base, 1_500L, outputPixels = 7_999_999L)
        val standard = resolver.resolve("model-a", base, 1_500L, outputPixels = 8_000_000L)
        val large = resolver.resolve("model-a", base, 1_500L, outputPixels = 24_000_001L)

        assertEquals(listOf(768, 256, 128), listOf(small.tileSize, standard.tileSize, large.tileSize))
        assertEquals(listOf(2, 4, 6), listOf(small.cpuThreads, standard.cpuThreads, large.cpuThreads))
        assertEquals(
            listOf(0, 20, 50),
            listOf(small.openmpBlocktimeMs, standard.openmpBlocktimeMs, large.openmpBlocktimeMs)
        )
        assertEquals(
            listOf(1, 2, 4),
            listOf(small.postprocessThreads, standard.postprocessThreads, large.postprocessThreads)
        )
        listOf(small, standard, large).forEach { resolved ->
            assertEquals(AccelerationMode.CPU, resolved.accelerationMode)
            assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
            assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        }
    }

    @Test
    fun vulkanAndAutoCarryCpuFallbackControlsWithoutReplacingVulkanTile() = runBlocking {
        val base = params().copy(
            cpuThreads = 3,
            openmpBlocktimeMs = 33,
            postprocessThreads = 3
        )
        val benchmark = benchmark(
            identity = identity(base),
            profiles = resolutionProfiles(),
            cpuProfiles = cpuResolutionProfiles()
        )
        val resolver = resolver(FakeBenchmarkRepository(benchmark))

        val vulkan = resolver.resolve("model-a", base, 1_500L, outputPixels = 7_999_999L)
        val auto = resolver.resolve(
            "model-a",
            base.copy(accelerationMode = AccelerationMode.AUTO),
            1_500L,
            outputPixels = 7_999_999L
        )

        listOf(vulkan, auto).forEach { resolved ->
            assertEquals(768, resolved.tileSize)
            assertEquals(8, resolved.commandBatchSize)
            assertEquals(2, resolved.cpuThreads)
            assertEquals(0, resolved.openmpBlocktimeMs)
            assertEquals(1, resolved.postprocessThreads)
        }
    }

    @Test
    fun missingCpuTierPreservesOriginalFallbackControls() = runBlocking {
        val base = params().copy(
            accelerationMode = AccelerationMode.CPU,
            cpuThreads = 3,
            openmpBlocktimeMs = 33,
            postprocessThreads = 3
        )
        val benchmark = benchmark(
            identity = identity(base),
            cpuProfiles = listOf(cpuResolutionProfiles().first())
        )

        val resolved = resolver(FakeBenchmarkRepository(benchmark)).resolve(
            "model-a",
            base,
            1_500L,
            outputPixels = 24_000_001L
        )

        assertEquals(base.tileSize, resolved.tileSize)
        assertEquals(base.cpuThreads, resolved.cpuThreads)
        assertEquals(base.openmpBlocktimeMs, resolved.openmpBlocktimeMs)
        assertEquals(base.postprocessThreads, resolved.postprocessThreads)
        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
    }

    @Test
    fun missingTierOrInvalidOutputPixelsFallsBackToSafeFields() = runBlocking {
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

        assertEquals(512 to 1, missingTier.tileSize to missingTier.commandBatchSize)
        assertEquals(NativeBackend.LEGACY_SAFE, missingTier.nativeBackend)
        assertEquals(InferencePrecision.FP32, missingTier.inferencePrecision)
        assertEquals(512 to 1, invalidPixels.tileSize to invalidPixels.commandBatchSize)
        assertEquals(NativeBackend.LEGACY_SAFE, invalidPixels.nativeBackend)
        assertEquals(InferencePrecision.FP32, invalidPixels.inferencePrecision)
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
    fun ordinaryBenchmarkCannotUnlockOptimizedTta() = runBlocking {
        val base = params().copy(tta = true)
        val resolver = resolver(
            repository = FakeBenchmarkRepository(benchmark(identity(base), batchSize = 8))
        )

        val resolved = resolver.resolve("model-a", base, nowEpochMs = 1_500L)

        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        assertTrue(resolved.tta)
        assertEquals(256, resolved.tileSize)
        assertEquals(1, resolved.commandBatchSize)
        assertTrue(resolved.gpuHeadroomPercent >= 10)
    }

    @Test
    fun exactQualifiedTtaProfileUnlocksOptimizedFp16BatchTwo() = runBlocking {
        val base = params().copy(tta = true)
        val resolver = resolver(
            repository = FakeBenchmarkRepository(
                benchmark(identity(base), ttaProfiles = listOf(ttaProfile(base)))
            )
        )

        val resolved = resolver.resolve("model-a", base, nowEpochMs = 1_500L)

        assertEquals(NativeBackend.OPTIMIZED, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP16, resolved.inferencePrecision)
        assertTrue(resolved.tta)
        assertEquals(ttaExecutionTile(base.tileSize), resolved.tileSize)
        assertEquals(2, resolved.commandBatchSize)
        assertEquals(2, resolved.modelCacheCapacity)
    }

    @Test
    fun missingStaleOrMismatchedTtaEvidenceFailsClosed() = runBlocking {
        val base = params().copy(tta = true)
        val exact = ttaProfile(base)
        val benchmarks = listOf(
            benchmark(identity(base)),
            benchmark(identity(base), validUntil = 1_499L, ttaProfiles = listOf(exact)),
            benchmark(identity(base), ttaProfiles = listOf(exact.copy(scale = base.scale + 1))),
            benchmark(identity(base), ttaProfiles = listOf(exact.copy(noise = base.noise + 1))),
            benchmark(identity(base), ttaProfiles = listOf(exact.copy(tileSize = base.tileSize)))
        )

        benchmarks.forEach { benchmark ->
            val resolved = resolver(FakeBenchmarkRepository(benchmark)).resolve(
                "model-a",
                base,
                nowEpochMs = 1_500L
            )
            assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
            assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
            assertEquals(1, resolved.commandBatchSize)
            assertEquals(1, resolved.modelCacheCapacity)
            assertTrue(resolved.tta)
        }
    }

    @Test
    fun ttaQualityAndTenPercentSpeedGatesUseInclusiveBoundaries() = runBlocking {
        val base = params().copy(tta = true)
        val exactBoundary = ttaProfile(base).copy(
            quality = Fp16QualityMetrics(ssim = 0.995, psnrDb = 40.0, p99ChannelError = 3),
            safeDurationNanos = 1_000L,
            optimizedDurationNanos = 900L
        )
        val passing = resolver(
            FakeBenchmarkRepository(
                benchmark(identity(base), ttaProfiles = listOf(exactBoundary))
            )
        ).resolve("model-a", base, nowEpochMs = 1_500L)
        assertEquals(NativeBackend.OPTIMIZED, passing.nativeBackend)
        assertEquals(2, passing.commandBatchSize)

        listOf(
            exactBoundary.copy(quality = exactBoundary.quality.copy(ssim = 0.9949)),
            exactBoundary.copy(quality = exactBoundary.quality.copy(ssim = 1.001)),
            exactBoundary.copy(quality = exactBoundary.quality.copy(psnrDb = 39.99)),
            exactBoundary.copy(quality = exactBoundary.quality.copy(p99ChannelError = 4)),
            exactBoundary.copy(quality = exactBoundary.quality.copy(p99ChannelError = -1)),
            exactBoundary.copy(optimizedDurationNanos = 901L)
        ).forEach { rejected ->
            val resolved = resolver(
                FakeBenchmarkRepository(
                    benchmark(identity(base), ttaProfiles = listOf(rejected))
                )
            ).resolve("model-a", base, nowEpochMs = 1_500L)
            assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
            assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
            assertEquals(1, resolved.commandBatchSize)
        }
    }

    @Test
    fun ordinaryCpuProfileCannotOverrideSafeTtaTile() = runBlocking {
        val base = params().copy(
            tta = true,
            accelerationMode = AccelerationMode.CPU
        )
        val benchmark = benchmark(
            identity(base),
            cpuProfiles = listOf(
                CalibratedCpuProfile(
                    ResolutionTier.STANDARD,
                    tileSize = 768,
                    cpuThreads = 2,
                    openmpBlocktimeMs = 0,
                    postprocessThreads = 1
                )
            )
        )

        val resolved = resolver(FakeBenchmarkRepository(benchmark)).resolve(
            "model-a",
            base,
            nowEpochMs = 1_500L
        )

        assertEquals(NativeBackend.LEGACY_SAFE, resolved.nativeBackend)
        assertEquals(InferencePrecision.FP32, resolved.inferencePrecision)
        assertEquals(256, resolved.tileSize)
        assertEquals(1, resolved.commandBatchSize)
        assertEquals(2, resolved.cpuThreads)
        assertEquals(0, resolved.openmpBlocktimeMs)
        assertEquals(1, resolved.postprocessThreads)
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
        profiles: List<CalibratedVulkanProfile> = emptyList(),
        cpuProfiles: List<CalibratedCpuProfile> = emptyList(),
        ttaProfiles: List<CalibratedTtaProfile> = emptyList()
    ) = InferenceBenchmarkResult(
        identity = identity,
        fp16Quality = Fp16QualityMetrics(0.999, 48.0, 1),
        recommendedCommandBatchSize = batchSize,
        measuredAtEpochMs = 1_000L,
        validUntilEpochMs = validUntil,
        vulkanProfiles = profiles,
        cpuProfiles = cpuProfiles,
        ttaProfiles = ttaProfiles
    )

    private fun ttaProfile(params: UpscaleParams) = CalibratedTtaProfile(
        scale = params.scale,
        noise = params.noise,
        tileSize = ttaExecutionTile(params.tileSize),
        passBatchSize = 2,
        quality = Fp16QualityMetrics(0.999, 48.0, 1),
        safeDurationNanos = 1_000L,
        optimizedDurationNanos = 900L
    )

    private fun ttaExecutionTile(requestedTileSize: Int): Int {
        val sanitized = UpscaleParamsFactory.sanitizeTileSize(requestedTileSize)
        return UpscaleParamsFactory.supportedTileSizes
            .filter { it < sanitized }
            .maxOrNull()
            ?: sanitized
    }

    private fun resolutionProfiles() = listOf(
        CalibratedVulkanProfile(ResolutionTier.SMALL, tileSize = 768, commandBatchSize = 8),
        CalibratedVulkanProfile(ResolutionTier.STANDARD, tileSize = 256, commandBatchSize = 4),
        CalibratedVulkanProfile(ResolutionTier.LARGE, tileSize = 128, commandBatchSize = 2)
    )

    private fun cpuResolutionProfiles() = listOf(
        CalibratedCpuProfile(ResolutionTier.SMALL, 768, 2, 0, 1),
        CalibratedCpuProfile(ResolutionTier.STANDARD, 256, 4, 20, 2),
        CalibratedCpuProfile(ResolutionTier.LARGE, 128, 6, 50, 4)
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
