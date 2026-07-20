package com.lumasr.data

import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ModelAssetRepositoryTest {
    @Test
    fun copiesBuiltInModelAssetsToTaskCachePath() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val source = FakeModelAssetSource(
            mapOf(
                "models/waifu2x/models-cunet/scale2.0x_model.param" to "param-data".toByteArray(),
                "models/waifu2x/models-cunet/scale2.0x_model.bin" to "bin-data".toByteArray()
            )
        )
        val repository = ModelAssetRepository(source, cacheRoot)

        val preparedDir = repository.prepareModel(cunetModel())

        assertEquals(File(cacheRoot, "models/waifu2x-cunet").canonicalPath, preparedDir.canonicalPath)
        assertEquals("param-data", File(preparedDir, "scale2.0x_model.param").readText())
        assertEquals("bin-data", File(preparedDir, "scale2.0x_model.bin").readText())
    }

    @Test
    fun rejectsMissingRequiredBuiltInModelFile() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val source = FakeModelAssetSource(
            mapOf("models/waifu2x/models-cunet/scale2.0x_model.param" to "param-data".toByteArray())
        )
        val repository = ModelAssetRepository(source, cacheRoot)

        val result = runCatching { repository.prepareModel(cunetModel()) }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("scale2.0x_model.bin"))
    }

    @Test
    fun skipsCopyWhenPreparedModelFilesAlreadyMatchAssetSize() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val source = FakeModelAssetSource(
            mapOf(
                "models/waifu2x/models-cunet/scale2.0x_model.param" to "param-data".toByteArray(),
                "models/waifu2x/models-cunet/scale2.0x_model.bin" to "bin-data".toByteArray()
            )
        )
        val repository = ModelAssetRepository(source, cacheRoot)

        repository.prepareModel(cunetModel())
        source.resetOpenCounts()
        repository.prepareModel(cunetModel())

        assertEquals(0, source.totalOpenCount)
    }

    @Test
    fun recopiesPreparedModelFileWhenCachedSizeDiffers() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val source = FakeModelAssetSource(
            mapOf(
                "models/waifu2x/models-cunet/scale2.0x_model.param" to "param-data".toByteArray(),
                "models/waifu2x/models-cunet/scale2.0x_model.bin" to "bin-data".toByteArray()
            )
        )
        val repository = ModelAssetRepository(source, cacheRoot)
        val preparedDir = repository.prepareModel(cunetModel())
        File(preparedDir, "scale2.0x_model.bin").writeText("stale")

        source.resetOpenCounts()
        repository.prepareModel(cunetModel())

        assertEquals(1, source.openCount("models/waifu2x/models-cunet/scale2.0x_model.bin"))
        assertEquals("bin-data", File(preparedDir, "scale2.0x_model.bin").readText())
    }

    @Test
    fun preparesOnlyRequestedVariantAndReportsActualByteProgress() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val requestedParam = "noise0_scale2.0x_model.param"
        val requestedBin = "noise0_scale2.0x_model.bin"
        val unusedParam = "noise3_scale2.0x_model.param"
        val unusedBin = "noise3_scale2.0x_model.bin"
        val source = FakeModelAssetSource(
            mapOf(
                "models/waifu2x/models-photo/$requestedParam" to "param-data".toByteArray(),
                "models/waifu2x/models-photo/$requestedBin" to ByteArray(600_000) { 7 },
                "models/waifu2x/models-photo/$unusedParam" to "unused-param".toByteArray(),
                "models/waifu2x/models-photo/$unusedBin" to "unused-bin".toByteArray()
            )
        )
        val repository = ModelAssetRepository(source, cacheRoot)
        val progress = mutableListOf<ModelPreparationProgress>()

        val preparedDir = repository.prepareModel(
            model = photoModel(
                listOf(requestedParam, requestedBin, unusedParam, unusedBin)
            ),
            requiredFiles = listOf(requestedParam, requestedBin),
            onProgress = progress::add
        )

        assertTrue(File(preparedDir, requestedParam).isFile)
        assertTrue(File(preparedDir, requestedBin).isFile)
        assertFalse(File(preparedDir, unusedParam).exists())
        assertFalse(File(preparedDir, unusedBin).exists())
        assertEquals(0, source.openCount("models/waifu2x/models-photo/$unusedParam"))
        assertEquals(0, source.openCount("models/waifu2x/models-photo/$unusedBin"))
        assertTrue(progress.size > 2)
        assertEquals(0L, progress.first().completedBytes)
        assertEquals(600_010L, progress.last().completedBytes)
        assertEquals(600_010L, progress.last().totalBytes)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun cancellationDuringCopyLeavesNoPartialModelFile() {
        val cacheRoot = Files.createTempDirectory("lumasr-model-cache").toFile()
        val requestedBin = "noise0_scale2.0x_model.bin"
        val source = FakeModelAssetSource(
            mapOf(
                "models/waifu2x/models-photo/$requestedBin" to ByteArray(600_000) { 7 }
            )
        )
        val repository = ModelAssetRepository(source, cacheRoot)
        var checks = 0

        val result = runCatching {
            repository.prepareModel(
                model = photoModel(listOf(requestedBin)),
                requiredFiles = listOf(requestedBin),
                cancellationCheck = {
                    checks += 1
                    if (checks >= 3) error("cancelled")
                }
            )
        }

        assertEquals("cancelled", result.exceptionOrNull()?.message)
        assertFalse(File(cacheRoot, "models/waifu2x-photo/$requestedBin").exists())
        assertTrue(checks >= 3)
    }

    private fun cunetModel() = ModelPack(
        id = "waifu2x-cunet",
        displayName = "CUnet",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models-cunet",
        assetPath = "models/waifu2x/models-cunet",
        isBuiltIn = true,
        requiredFiles = listOf("scale2.0x_model.param", "scale2.0x_model.bin"),
        assetBytes = null,
        description = "Quality-first illustration cleanup.",
        scenes = listOf("anime"),
        scales = listOf(2),
        denoise = listOf(1),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 1,
        speedLevel = "slow",
        qualityLevel = "high"
    )

    private fun photoModel(requiredFiles: List<String>) = ModelPack(
        id = "waifu2x-photo",
        displayName = "Photo",
        engine = SuperResEngine.WAIFU2X,
        modelDir = "models-photo",
        assetPath = "models/waifu2x/models-photo",
        isBuiltIn = true,
        requiredFiles = requiredFiles,
        assetBytes = null,
        description = "Photo cleanup.",
        scenes = listOf("photo"),
        scales = listOf(2),
        denoise = listOf(0, 3),
        supportsTta = true,
        defaultScale = 2,
        defaultNoise = 0,
        speedLevel = "medium",
        qualityLevel = "medium"
    )
}

private class FakeModelAssetSource(
    private val files: Map<String, ByteArray>
) : ModelAssetSource {
    private val openCounts = mutableMapOf<String, Int>()
    val totalOpenCount: Int
        get() = openCounts.values.sum()

    override fun open(path: String): InputStream {
        openCounts[path] = openCounts.getOrDefault(path, 0) + 1
        return ByteArrayInputStream(files.getValue(path))
    }

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun size(path: String): Long? = files[path]?.size?.toLong()

    fun openCount(path: String): Int = openCounts.getOrDefault(path, 0)

    fun resetOpenCounts() {
        openCounts.clear()
    }
}
