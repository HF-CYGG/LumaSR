package com.lumasr.data

import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
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
