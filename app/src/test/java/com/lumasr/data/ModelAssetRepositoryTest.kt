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
    override fun open(path: String): InputStream {
        return ByteArrayInputStream(files.getValue(path))
    }

    override fun exists(path: String): Boolean = files.containsKey(path)
}
