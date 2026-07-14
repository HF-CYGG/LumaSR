package com.lumasr.data

import com.lumasr.domain.BenchmarkIdentity
import com.lumasr.domain.Fp16QualityMetrics
import com.lumasr.domain.InferenceBenchmarkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileInferenceBenchmarkRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
            base.copy(profileHash = "profile-b"),
            base.copy(deviceFingerprint = DEVICE_HASH_B),
            base.copy(gpuDriverVersion = "driver-v2")
        )

        identities.forEachIndexed { index, value ->
            repository.save(result(value, batchSize = listOf(1, 2, 4, 8, 1, 2)[index]))
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
        validUntil: Long = 2_000L
    ) = InferenceBenchmarkResult(
        identity = identity,
        fp16Quality = quality,
        recommendedCommandBatchSize = batchSize,
        measuredAtEpochMs = measuredAt,
        validUntilEpochMs = validUntil
    )

    private companion object {
        const val DEVICE_HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
