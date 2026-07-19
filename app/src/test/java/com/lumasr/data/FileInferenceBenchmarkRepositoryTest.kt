package com.lumasr.data

import com.lumasr.domain.BenchmarkIdentity
import com.lumasr.domain.BenchmarkPublicationGate
import com.lumasr.domain.CalibratedCpuProfile
import com.lumasr.domain.CalibratedQnnProfile
import com.lumasr.domain.CalibratedTtaProfile
import com.lumasr.domain.CalibratedVulkanProfile
import com.lumasr.domain.CalibratedVulkanTier
import com.lumasr.domain.CalibrationFallbackReason
import com.lumasr.domain.CalibrationMeasuredBackend
import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationPerformanceGrade
import com.lumasr.domain.CalibrationTierOutcome
import com.lumasr.domain.Fp16QualityMetrics
import com.lumasr.domain.InferenceBenchmarkResult
import com.lumasr.domain.QnnPerformanceMode
import com.lumasr.domain.QnnPrecision
import com.lumasr.domain.ResolutionTier
import com.lumasr.domain.VulkanCalibrationDecision
import com.lumasr.domain.VulkanExecutionTuning
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class FileInferenceBenchmarkRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsSchemaV8GradesTierOutcomesAndQnnProfilesWithoutRawDeviceData() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v7-qnn")
        val repository = FileInferenceBenchmarkRepository(root)
        val expected = result(
            identity = identity(),
            overallGrade = CalibrationPerformanceGrade.STABLE,
            tierOutcomes = listOf(
                CalibrationTierOutcome(
                    resolutionTier = ResolutionTier.STANDARD,
                    grade = CalibrationPerformanceGrade.STABLE,
                    backend = CalibrationMeasuredBackend.VULKAN,
                    baselineDurationNanos = 2_000L,
                    selectedDurationNanos = 1_950L,
                    speedupPercent = 2.5,
                    fallbackReason = CalibrationFallbackReason.PERFORMANCE_GATE
                )
            ),
            qnnProfiles = listOf(
                CalibratedQnnProfile(
                    resolutionTier = ResolutionTier.LARGE,
                    graphKey = "realcugan-x4-fp16-v73",
                    precision = QnnPrecision.FP16,
                    tileSize = 256,
                    maxPipelineDepth = 2,
                    performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
                    quality = passingQuality(),
                    ncnnMedianDurationNanos = 2_000L,
                    qnnMedianDurationNanos = 1_600L,
                    ncnnP95DurationNanos = 2_400L,
                    qnnP95DurationNanos = 2_000L,
                    sampleCount = 30,
                    measurementDurationMs = 60_000L
                )
            )
        )

        repository.save(expected)

        assertEquals(listOf(expected), repository.list())
        val json = File(root, "inference-benchmarks.json").readText()
        assertTrue(json.contains("\"schemaVersion\":8"))
        assertTrue(json.contains("\"overallGrade\":\"STABLE\""))
        assertTrue(json.contains("\"fallbackReason\":\"PERFORMANCE_GATE\""))
        assertTrue(json.contains("\"graphKey\":\"realcugan-x4-fp16-v73\""))
        assertFalse(json.contains("SM8650"))
        assertFalse(json.contains("/storage/"))
    }

    @Test
    fun schemaV6RecordsDefaultToEmptyQnnProfiles() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v6-compatible")
        val repository = FileInferenceBenchmarkRepository(root)
        repository.save(result(identity = identity()))
        val record = File(root, "inference-benchmarks.json")
        record.writeText(
            record.readText()
                .replace("\"schemaVersion\":8", "\"schemaVersion\":6")
                .replace(",\"overallGrade\":\"COMPATIBLE\"", "")
                .replace(",\"tierOutcomes\":[]", "")
                .replace(",\"qnnProfiles\":[]", "")
        )

        val restored = repository.list()

        assertEquals(1, restored.size)
        assertTrue(restored.single().qnnProfiles.isEmpty())
    }

    @Test
    fun schemaV7InfersHighPerformanceForOptimizedAndCompatibleForSafeRecords() = runBlocking {
        suspend fun restoredGrade(
            folder: String,
            profiles: List<CalibratedVulkanProfile>
        ): CalibrationPerformanceGrade {
            val root = temporaryFolder.newFolder(folder)
            val repository = FileInferenceBenchmarkRepository(root)
            repository.save(result(identity = identity(), vulkanProfiles = profiles))
            val record = File(root, "inference-benchmarks.json")
            record.writeText(
                record.readText()
                    .replace("\"schemaVersion\":8", "\"schemaVersion\":7")
                    .replace(Regex(",\"overallGrade\":\"[A-Z_]+\""), "")
                    .replace(",\"tierOutcomes\":[]", "")
            )
            return repository.list().single().overallGrade
        }

        val optimized = CalibratedVulkanProfile(
            resolutionTier = ResolutionTier.STANDARD,
            tileSize = 256,
            commandBatchSize = 2
        )

        assertEquals(
            CalibrationPerformanceGrade.HIGH_PERFORMANCE,
            restoredGrade("legacy-v7-optimized", listOf(optimized))
        )
        assertEquals(
            CalibrationPerformanceGrade.COMPATIBLE,
            restoredGrade("legacy-v7-safe", emptyList())
        )
    }

    @Test
    fun persistsSchemaV7CalibrationModeAndVulkanTuning() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v7-adreno-tuning")
        val repository = FileInferenceBenchmarkRepository(root)
        val tuning = VulkanExecutionTuning(
            useSubgroupOps = false,
            useShaderLocalMemory = true,
            useWinogradConvolution = false,
            useSgemmConvolution = true
        )
        val expected = result(
            identity = identity(),
            calibrationMode = CalibrationMode.QUICK,
            vulkanProfiles = listOf(
                CalibratedVulkanProfile(
                    resolutionTier = ResolutionTier.STANDARD,
                    tileSize = 128,
                    commandBatchSize = 2,
                    tuning = tuning
                )
            )
        )

        repository.save(expected)

        assertEquals(listOf(expected), repository.list())
        val json = File(root, "inference-benchmarks.json").readText()
        assertTrue(json.contains("\"schemaVersion\":8"))
        assertTrue(json.contains("\"calibrationMode\":\"QUICK\""))
        assertTrue(json.contains("\"useSubgroupOps\":false"))
    }

    @Test
    fun quickPublicationCannotDowngradeDeepCalibrationForSameIdentity() = runBlocking {
        val root = temporaryFolder.newFolder("deep-priority")
        val repository = FileInferenceBenchmarkRepository(root)
        val deep = result(identity(), calibrationMode = CalibrationMode.DEEP, measuredAt = 1_000L)
        val quick = result(identity(), calibrationMode = CalibrationMode.QUICK, measuredAt = 2_000L)
        repository.save(deep)

        repository.save(quick)

        assertEquals(listOf(deep), repository.list())
    }

    @Test
    fun persistsSchemaV7PositiveAndNegativeVulkanTierEvidence() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v5-tier-evidence")
        val repository = FileInferenceBenchmarkRepository(root)
        val enabledProfile = CalibratedVulkanProfile(
            resolutionTier = ResolutionTier.SMALL,
            tileSize = 128,
            commandBatchSize = 2,
            maxPipelineDepth = 2
        )
        val expected = result(
            identity = identity(),
            vulkanProfiles = listOf(enabledProfile),
            vulkanTiers = listOf(
                CalibratedVulkanTier(
                    resolutionTier = ResolutionTier.SMALL,
                    decision = VulkanCalibrationDecision.ENABLED,
                    baselineDurationNanos = 1_000L,
                    bestCandidateDurationNanos = 800L,
                    profile = enabledProfile
                ),
                CalibratedVulkanTier(
                    resolutionTier = ResolutionTier.STANDARD,
                    decision = VulkanCalibrationDecision.PERFORMANCE_REJECTED,
                    baselineDurationNanos = 1_000L,
                    bestCandidateDurationNanos = 1_050L
                ),
                CalibratedVulkanTier(
                    resolutionTier = ResolutionTier.LARGE,
                    decision = VulkanCalibrationDecision.QUALITY_REJECTED
                )
            )
        )

        repository.save(expected)

        assertEquals(listOf(expected), repository.list())
        val json = File(root, "inference-benchmarks.json").readText()
        assertTrue(json.contains("\"schemaVersion\":8"))
        assertTrue(json.contains("\"decision\":\"PERFORMANCE_REJECTED\""))
        assertTrue(json.contains("\"maxPipelineDepth\":2"))
    }

    @Test
    fun cancellationRequestDoesNotBlockBehindCommitWork() = runBlocking {
        val gate = BenchmarkPublicationGate()
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val newRecordVisible = AtomicBoolean(false)
        val commit = async(Dispatchers.Default) {
            gate.tryCommit(
                commit = {
                    newRecordVisible.set(true)
                    commitStarted.countDown()
                    releaseCommit.await()
                },
                rollback = { newRecordVisible.set(false) }
            )
        }
        commitStarted.await()
        val safetyRelease = launch(Dispatchers.Default) {
            delay(1_000L)
            releaseCommit.countDown()
        }

        try {
            val startedAt = System.nanoTime()
            val cancelAccepted = gate.cancel()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue(cancelAccepted)
            assertTrue("cancel must not block behind file commit work", elapsedMs < 750L)
        } finally {
            releaseCommit.countDown()
            safetyRelease.cancel()
            assertFalse(commit.await())
        }
        assertFalse(newRecordVisible.get())
    }

    @Test
    fun roundTripsMultipleResultsAndPositiveInfinityPsnr() = runBlocking {
        val root = temporaryFolder.newFolder("round-trip")
        val repository = FileInferenceBenchmarkRepository(root)
        val first = result(identity = identity(profileHash = "profile-a"))
        val second = result(
            identity = identity(profileHash = "profile-b"),
            quality = passingQuality().copy(psnrDb = Double.POSITIVE_INFINITY),
            batchSize = 8
        )

        repository.save(first)
        repository.save(second)

        assertEquals(listOf(first, second), repository.list())
    }

    @Test
    fun roundTripsResolutionAwareVulkanCpuAndTtaProfiles() = runBlocking {
        val root = temporaryFolder.newFolder("resolution-profile-round-trip")
        val repository = FileInferenceBenchmarkRepository(root)
        val expected = result(
            identity = identity(),
            vulkanProfiles = listOf(
                CalibratedVulkanProfile(ResolutionTier.SMALL, tileSize = 768, commandBatchSize = 8),
                CalibratedVulkanProfile(ResolutionTier.STANDARD, tileSize = 512, commandBatchSize = 4),
                CalibratedVulkanProfile(ResolutionTier.LARGE, tileSize = 256, commandBatchSize = 2)
            ),
            cpuProfiles = listOf(
                CalibratedCpuProfile(
                    ResolutionTier.SMALL,
                    tileSize = 512,
                    cpuThreads = 6,
                    openmpBlocktimeMs = 20,
                    postprocessThreads = 4
                ),
                CalibratedCpuProfile(
                    ResolutionTier.STANDARD,
                    tileSize = 256,
                    cpuThreads = 4,
                    openmpBlocktimeMs = 40,
                    postprocessThreads = 3
                ),
                CalibratedCpuProfile(
                    ResolutionTier.LARGE,
                    tileSize = 128,
                    cpuThreads = 2,
                    openmpBlocktimeMs = 60,
                    postprocessThreads = 2
                )
            ),
            ttaProfiles = listOf(
                CalibratedTtaProfile(
                    scale = 2,
                    noise = 0,
                    tileSize = 128,
                    passBatchSize = 2,
                    quality = passingQuality(),
                    safeDurationNanos = 1_000L,
                    optimizedDurationNanos = 900L
                )
            )
        )

        repository.save(expected)

        assertEquals(listOf(expected), repository.list())
        assertTrue(File(root, "inference-benchmarks.json").readText().contains("\"schemaVersion\":8"))
    }

    @Test
    fun schemaV3RecordsRemainValidWithEmptyTtaEvidence() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v3-compatible")
        File(root, "inference-benchmarks.json").writeText(
            encodedV3Aggregate(encodedV3Result(profileHash = "legacy-v3"))
        )

        val records = FileInferenceBenchmarkRepository(root).list()

        assertEquals(1, records.size)
        assertTrue(records.single().ttaProfiles.isEmpty())
    }

    @Test
    fun schemaV3DropsNonObjectItemWithoutQuarantiningValidRecord() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v3-mixed-non-object")
        val record = File(root, "inference-benchmarks.json")
        record.writeText(
            encodedV3Aggregate(
                encodedV3Result(profileHash = "valid-profile"),
                "7"
            )
        )

        val records = FileInferenceBenchmarkRepository(root).list()

        assertEquals(listOf(resolutionAwareResult("valid-profile")), records)
        assertTrue(records.single().ttaProfiles.isEmpty())
        assertTrue(record.exists())
        assertTrue(record.readText().contains("\"schemaVersion\":8"))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun rejectsCorruptTtaProfileInSchemaV4() = runBlocking {
        val root = temporaryFolder.newFolder("corrupt-tta-v4")
        val record = File(root, "inference-benchmarks.json")
        val repository = FileInferenceBenchmarkRepository(root)
        repository.save(
            result(
                identity = identity(),
                ttaProfiles = listOf(
                    CalibratedTtaProfile(
                        scale = 2,
                        noise = 0,
                        tileSize = 128,
                        passBatchSize = 2,
                        quality = passingQuality(),
                        safeDurationNanos = 1_000L,
                        optimizedDurationNanos = 900L
                    )
                )
            )
        )
        record.writeText(record.readText().replace("\"passBatchSize\":2", "\"passBatchSize\":4"))

        assertEquals(emptyList<InferenceBenchmarkResult>(), repository.list())
        assertFalse(record.exists())
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun schemaV2RecordsAreInvalidated() = runBlocking {
        val root = temporaryFolder.newFolder("schema-v2")
        File(root, "inference-benchmarks.json").writeText(
            """{"schemaVersion":2,"results":[${encodedLegacyResult()}]}"""
        )

        assertEquals(emptyList<InferenceBenchmarkResult>(), FileInferenceBenchmarkRepository(root).list())
        assertFalse(File(root, "inference-benchmarks.json").exists())
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun dropsOnlyRecordWithUnknownResolutionTierAndAtomicallyKeepsValidCalibration() = runBlocking {
        val root = temporaryFolder.newFolder("mixed-unknown-tier")
        val record = File(root, "inference-benchmarks.json")
        record.writeText(
            encodedV3Aggregate(
                encodedV3Result(profileHash = "valid-profile"),
                encodedV3Result(
                    profileHash = "invalid-tier-profile",
                    vulkanResolutionTier = "UNKNOWN"
                )
            )
        )
        val repository = FileInferenceBenchmarkRepository(root)
        val expected = resolutionAwareResult("valid-profile")

        assertEquals(listOf(expected), repository.list())
        assertEquals(listOf(expected), repository.list())
        assertTrue(record.exists())
        assertTrue(record.readText().contains("\"profileHash\":\"valid-profile\""))
        assertFalse(record.readText().contains("invalid-tier-profile"))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun dropsOnlyRecordWithInvalidProfileNumberAndKeepsValidCalibration() = runBlocking {
        val root = temporaryFolder.newFolder("mixed-invalid-number")
        val record = File(root, "inference-benchmarks.json")
        record.writeText(
            encodedV3Aggregate(
                encodedV3Result(profileHash = "valid-profile"),
                encodedV3Result(
                    profileHash = "invalid-number-profile",
                    cpuThreads = 0
                )
            )
        )
        val repository = FileInferenceBenchmarkRepository(root)
        val expected = resolutionAwareResult("valid-profile")

        assertEquals(listOf(expected), repository.list())
        assertTrue(record.exists())
        assertTrue(record.readText().contains("\"profileHash\":\"valid-profile\""))
        assertFalse(record.readText().contains("invalid-number-profile"))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun destructiveRecoveryMoveFailureRestoresOriginalBytesAndReturnsValidCalibration() = runBlocking {
        val root = temporaryFolder.newFolder("failed-recovery-rewrite")
        val record = File(root, "inference-benchmarks.json")
        record.writeText(
            encodedV3Aggregate(
                encodedV3Result(profileHash = "valid-profile"),
                encodedV3Result(
                    profileHash = "invalid-tier-profile",
                    vulkanResolutionTier = "UNKNOWN"
                )
            )
        )
        val originalBytes = record.readBytes()
        val repository = FileInferenceBenchmarkRepository(
            directory = root,
            atomicMove = { source, destination ->
                if (source.name.endsWith(".tmp") && destination == record) {
                    Files.deleteIfExists(destination.toPath())
                    error("simulated destructive recovery move failure")
                }
                moveReplacingForTest(source, destination)
            }
        )
        val expected = resolutionAwareResult("valid-profile")

        assertEquals(listOf(expected), repository.list())
        assertTrue(record.exists())
        assertArrayEquals(originalBytes, record.readBytes())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".rollback") })
    }

    @Test
    fun rejectsDuplicateResolutionTierProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            result(
                identity = identity(),
                vulkanProfiles = listOf(
                    CalibratedVulkanProfile(ResolutionTier.STANDARD, 512, 2),
                    CalibratedVulkanProfile(ResolutionTier.STANDARD, 256, 4)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            result(
                identity = identity(),
                cpuProfiles = listOf(
                    CalibratedCpuProfile(ResolutionTier.LARGE, 256, 4, 20, 2),
                    CalibratedCpuProfile(ResolutionTier.LARGE, 128, 2, 40, 2)
                )
            )
        }
    }

    @Test
    fun replacesOnlyTheRecordWithTheSameCompleteIdentity() = runBlocking {
        val root = temporaryFolder.newFolder("replace")
        val repository = FileInferenceBenchmarkRepository(root)
        val identityA = identity(profileHash = "profile-a")
        val identityB = identity(profileHash = "profile-b")
        repository.save(result(identityA, batchSize = 1))
        repository.save(result(identityB, batchSize = 2))

        repository.save(result(identityA, batchSize = 4, measuredAt = 2_000L, validUntil = 3_000L))

        assertEquals(
            listOf(
                result(identityA, batchSize = 4, measuredAt = 2_000L, validUntil = 3_000L),
                result(identityB, batchSize = 2)
            ),
            repository.list()
        )
    }

    @Test
    fun completeIdentityKeepsSchemaEngineProfileDeviceAndDriverDistinct() = runBlocking {
        val root = temporaryFolder.newFolder("identity")
        val repository = FileInferenceBenchmarkRepository(root)
        val base = identity()
        val identities = listOf(
            base,
            base.copy(schemaKey = "benchmark-v2"),
            base.copy(engineKey = "ncnn-v2"),
            base.copy(
                modelArtifactHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            ),
            base.copy(runtimeBuildKey = "app-2:test:arm64-v8a"),
            base.copy(profileHash = "profile-b"),
            base.copy(deviceFingerprint = DEVICE_HASH_B),
            base.copy(gpuDriverVersion = "driver-v2")
        )

        identities.forEachIndexed { index, value ->
            repository.save(result(value, batchSize = listOf(1, 2, 4, 8)[index % 4]))
        }

        assertEquals(identities.toSet(), repository.list().map { it.identity }.toSet())
    }

    @Test
    fun findValidUsesInclusiveBoundsAndRejectsFutureExpiredOrDifferentIdentity() = runBlocking {
        val root = temporaryFolder.newFolder("validity")
        val repository = FileInferenceBenchmarkRepository(root)
        val expected = result(identity(), measuredAt = 1_000L, validUntil = 2_000L)
        repository.save(expected)

        assertEquals(expected, repository.findValid(expected.identity, nowEpochMs = 1_000L))
        assertEquals(expected, repository.findValid(expected.identity, nowEpochMs = 2_000L))
        assertNull(repository.findValid(expected.identity, nowEpochMs = 999L))
        assertNull(repository.findValid(expected.identity, nowEpochMs = 2_001L))
        assertNull(
            repository.findValid(
                expected.identity.copy(profileHash = "another-profile"),
                nowEpochMs = 1_500L
            )
        )
    }

    @Test
    fun corruptAndUnknownSchemaFilesAreQuarantinedWithoutCrashing() = runBlocking {
        val corruptRoot = temporaryFolder.newFolder("corrupt")
        File(corruptRoot, "inference-benchmarks.json").writeText("{not-json")
        val corruptRepository = FileInferenceBenchmarkRepository(corruptRoot)

        assertEquals(emptyList<InferenceBenchmarkResult>(), corruptRepository.list())
        assertFalse(File(corruptRoot, "inference-benchmarks.json").exists())
        assertTrue(corruptRoot.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })

        val schemaRoot = temporaryFolder.newFolder("unknown-schema")
        File(schemaRoot, "inference-benchmarks.json").writeText(
            """{"schemaVersion":999,"results":[]}"""
        )
        val schemaRepository = FileInferenceBenchmarkRepository(schemaRoot)

        assertEquals(emptyList<InferenceBenchmarkResult>(), schemaRepository.list())
        assertTrue(schemaRoot.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun quarantinesWrongJsonScalarTypesAndNonCanonicalInfinity() = runBlocking {
        val validIdentity = """
            {"schemaKey":"benchmark-v1","engineKey":"ncnn-v1:realcugan-x4plus",
             "modelArtifactHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "runtimeBuildKey":"app-1:test:arm64-v8a",
             "profileHash":"profile-a","deviceFingerprint":"$DEVICE_HASH_A",
             "gpuDriverVersion":"driver-v1"}
        """.trimIndent().replace("\n", "")
        val cases = listOf(
            "wrong-string-type" to validIdentity.replace("\"schemaKey\":\"benchmark-v1\"", "\"schemaKey\":1"),
            "quoted-number" to validIdentity,
            "overflow-number" to validIdentity,
            "noncanonical-infinity" to validIdentity
        )

        cases.forEach { (name, identityJson) ->
            val root = temporaryFolder.newFolder(name)
            val ssim = if (name == "quoted-number") "\"0.999\"" else "0.999"
            val psnr = when (name) {
                "overflow-number" -> "1e999"
                "noncanonical-infinity" -> "\"Infinity\""
                else -> "48.0"
            }
            File(root, "inference-benchmarks.json").writeText(
                """{"schemaVersion":3,"results":[{"identity":$identityJson,"quality":{"ssim":$ssim,"psnrDb":$psnr,"p99ChannelError":1},"recommendedCommandBatchSize":4,"vulkanProfiles":[],"cpuProfiles":[],"measuredAtEpochMs":1000,"validUntilEpochMs":2000}]}"""
            )

            assertEquals(emptyList<InferenceBenchmarkResult>(), FileInferenceBenchmarkRepository(root).list())
            assertFalse(File(root, "inference-benchmarks.json").exists())
            assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
        }
    }

    @Test
    fun writeIsAtomicLeavesNoTemporaryFileAndContainsNoRawDeviceOrImageData() = runBlocking {
        val root = temporaryFolder.newFolder("privacy")
        val repository = FileInferenceBenchmarkRepository(root)

        repository.save(result(identity()))

        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        val json = File(root, "inference-benchmarks.json").readText()
        assertFalse(json.contains("Xiaomi/aurora/user/release-keys"))
        assertFalse(json.contains("DCIM"))
        assertFalse(json.contains("/storage/"))
        assertTrue(json.contains(DEVICE_HASH_A))
    }

    @Test
    fun cancelledPublicationGateLeavesNoRecordOrTemporaryFile() = runBlocking {
        val root = temporaryFolder.newFolder("cancelled-publication")
        val repository = FileInferenceBenchmarkRepository(root)
        val gate = BenchmarkPublicationGate()
        assertTrue(gate.cancel())

        val published = repository.publish(result(identity()), gate)

        assertFalse(published)
        assertTrue(repository.list().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationDuringCommitRestoresOldRecordBeforeOtherRepositoryCanRead() = runBlocking {
        val root = temporaryFolder.newFolder("rollback-old-record")
        val oldResult = result(identity(), batchSize = 1)
        FileInferenceBenchmarkRepository(root).save(oldResult)
        val commitApplied = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val publisher = FileInferenceBenchmarkRepository(
            directory = root,
            atomicMove = blockingCommitMover(commitApplied, releaseCommit)
        )
        val reader = FileInferenceBenchmarkRepository(root)
        val gate = BenchmarkPublicationGate()
        val newResult = result(
            identity(),
            batchSize = 8,
            measuredAt = 2_000L,
            validUntil = 3_000L
        )
        val publication = async(Dispatchers.Default) {
            publisher.publish(newResult, gate)
        }
        commitApplied.await()
        val concurrentRead = async(Dispatchers.Default) { reader.list() }
        delay(50L)

        try {
            assertFalse("reader must stay behind repository commit/rollback", concurrentRead.isCompleted)
            assertTrue(gate.cancel())
        } finally {
            releaseCommit.countDown()
        }

        assertFalse(publication.await())
        assertEquals(listOf(oldResult), concurrentRead.await())
        assertEquals(listOf(oldResult), reader.list())
        assertFalse(root.listFiles().orEmpty().any { it.name.contains("rollback") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun cancellationDuringFirstCommitRemovesNewRecordBeforeReadersProceed() = runBlocking {
        val root = temporaryFolder.newFolder("rollback-first-record")
        val commitApplied = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val publisher = FileInferenceBenchmarkRepository(
            directory = root,
            atomicMove = blockingCommitMover(commitApplied, releaseCommit)
        )
        val reader = FileInferenceBenchmarkRepository(root)
        val gate = BenchmarkPublicationGate()
        val publication = async(Dispatchers.Default) {
            publisher.publish(result(identity()), gate)
        }
        commitApplied.await()
        val concurrentRead = async(Dispatchers.Default) { reader.list() }
        delay(50L)

        try {
            assertFalse(concurrentRead.isCompleted)
            assertTrue(gate.cancel())
        } finally {
            releaseCommit.countDown()
        }

        assertFalse(publication.await())
        assertTrue(concurrentRead.await().isEmpty())
        assertTrue(reader.list().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsUnsupportedBatchNonFiniteMetricsAndUnnormalizedIdentity() {
        val root = temporaryFolder.newFolder("invalid")
        val repository = FileInferenceBenchmarkRepository(root)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.save(result(identity(), batchSize = 3)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(
                    result(identity(), quality = passingQuality().copy(ssim = Double.NaN))
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            identity(profileHash = "profile\nvalue")
        }
        assertThrows(IllegalArgumentException::class.java) {
            identity().copy(deviceFingerprint = "raw/device/fingerprint")
        }
        assertThrows(IllegalArgumentException::class.java) {
            identity(gpuDriverVersion = "d".repeat(257))
        }
    }

    private fun identity(
        profileHash: String = "profile-a",
        gpuDriverVersion: String? = "driver-v1"
    ) = BenchmarkIdentity(
        schemaKey = "benchmark-v1",
        engineKey = "ncnn-v1:realcugan-x4plus",
        modelArtifactHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        runtimeBuildKey = "app-1:test:arm64-v8a",
        profileHash = profileHash,
        deviceFingerprint = DEVICE_HASH_A,
        gpuDriverVersion = gpuDriverVersion
    )

    private fun passingQuality() = Fp16QualityMetrics(
        ssim = 0.999,
        psnrDb = 48.0,
        p99ChannelError = 1
    )

    private fun result(
        identity: BenchmarkIdentity,
        quality: Fp16QualityMetrics = passingQuality(),
        batchSize: Int = 4,
        measuredAt: Long = 1_000L,
        validUntil: Long = 2_000L,
        vulkanProfiles: List<CalibratedVulkanProfile> = emptyList(),
        vulkanTiers: List<CalibratedVulkanTier> = vulkanProfiles.map {
            CalibratedVulkanTier(
                resolutionTier = it.resolutionTier,
                decision = VulkanCalibrationDecision.ENABLED,
                profile = it
            )
        },
        cpuProfiles: List<CalibratedCpuProfile> = emptyList(),
        ttaProfiles: List<CalibratedTtaProfile> = emptyList(),
        qnnProfiles: List<CalibratedQnnProfile> = emptyList(),
        calibrationMode: CalibrationMode = CalibrationMode.LEGACY,
        tierOutcomes: List<CalibrationTierOutcome> = emptyList(),
        overallGrade: CalibrationPerformanceGrade =
            tierOutcomes.firstOrNull { it.resolutionTier == ResolutionTier.STANDARD }?.grade
                ?: if (vulkanProfiles.any { it.resolutionTier == ResolutionTier.STANDARD }) {
                    CalibrationPerformanceGrade.HIGH_PERFORMANCE
                } else {
                    CalibrationPerformanceGrade.COMPATIBLE
                }
    ) = InferenceBenchmarkResult(
        identity = identity,
        fp16Quality = quality,
        recommendedCommandBatchSize = batchSize,
        vulkanProfiles = vulkanProfiles,
        vulkanTiers = vulkanTiers,
        cpuProfiles = cpuProfiles,
        ttaProfiles = ttaProfiles,
        qnnProfiles = qnnProfiles,
        calibrationMode = calibrationMode,
        tierOutcomes = tierOutcomes,
        overallGrade = overallGrade,
        measuredAtEpochMs = measuredAt,
        validUntilEpochMs = validUntil
    )

    private fun encodedLegacyResult(): String =
        """{"identity":{"schemaKey":"benchmark-v1","engineKey":"ncnn-v1:realcugan-x4plus","modelArtifactHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","runtimeBuildKey":"app-1:test:arm64-v8a","profileHash":"profile-a","deviceFingerprint":"$DEVICE_HASH_A","gpuDriverVersion":"driver-v1"},"quality":{"ssim":0.999,"psnrDb":48.0,"p99ChannelError":1},"recommendedCommandBatchSize":4,"measuredAtEpochMs":1000,"validUntilEpochMs":2000}"""

    private fun encodedV3Aggregate(vararg results: String): String =
        """{"schemaVersion":3,"results":[${results.joinToString(",")}]}"""

    private fun encodedV3Result(
        profileHash: String,
        vulkanResolutionTier: String = "STANDARD",
        cpuThreads: Int = 4
    ): String =
        """{"identity":{"schemaKey":"benchmark-v1","engineKey":"ncnn-v1:realcugan-x4plus","modelArtifactHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","runtimeBuildKey":"app-1:test:arm64-v8a","profileHash":"$profileHash","deviceFingerprint":"$DEVICE_HASH_A","gpuDriverVersion":"driver-v1"},"quality":{"ssim":0.999,"psnrDb":48.0,"p99ChannelError":1},"recommendedCommandBatchSize":4,"vulkanProfiles":[{"resolutionTier":"$vulkanResolutionTier","tileSize":512,"commandBatchSize":4}],"cpuProfiles":[{"resolutionTier":"STANDARD","tileSize":256,"cpuThreads":$cpuThreads,"openmpBlocktimeMs":20,"postprocessThreads":2}],"measuredAtEpochMs":1000,"validUntilEpochMs":2000}"""

    private fun resolutionAwareResult(profileHash: String): InferenceBenchmarkResult = result(
        identity = identity(profileHash = profileHash),
        vulkanProfiles = listOf(
            CalibratedVulkanProfile(ResolutionTier.STANDARD, tileSize = 512, commandBatchSize = 4)
        ),
        cpuProfiles = listOf(
            CalibratedCpuProfile(
                ResolutionTier.STANDARD,
                tileSize = 256,
                cpuThreads = 4,
                openmpBlocktimeMs = 20,
                postprocessThreads = 2
            )
        )
    )

    private fun blockingCommitMover(
        commitApplied: CountDownLatch,
        releaseCommit: CountDownLatch
    ): (File, File) -> Unit = { source, destination ->
        moveReplacingForTest(source, destination)
        if (destination.name == "inference-benchmarks.json" && source.name.endsWith(".tmp")) {
            commitApplied.countDown()
            releaseCommit.await()
        }
    }

    private fun moveReplacingForTest(source: File, destination: File) {
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
        const val DEVICE_HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
