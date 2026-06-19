package com.lumasr.data

import android.content.Context
import com.lumasr.domain.ModelPack
import java.io.File
import java.io.InputStream

interface ModelAssetSource {
    fun open(path: String): InputStream
    fun exists(path: String): Boolean
}

class AndroidModelAssetSource(
    private val context: Context
) : ModelAssetSource {
    override fun open(path: String): InputStream = context.assets.open(path)

    override fun exists(path: String): Boolean {
        return runCatching { context.assets.open(path).close() }.isSuccess
    }
}

class ModelAssetRepository(
    private val assetSource: ModelAssetSource,
    private val cacheRoot: File
) {
    fun prepareModel(model: ModelPack): File {
        require(model.isBuiltIn) { "Model ${model.id} is not marked as built-in." }
        require(model.assetPath.isNotBlank()) { "Model ${model.id} has no assetPath." }
        require(model.requiredFiles.isNotEmpty()) { "Model ${model.id} has no required files." }

        val targetDir = File(cacheRoot, "models/${model.id}").canonicalFile
        ensureInsideCache(targetDir)
        targetDir.mkdirs()

        model.requiredFiles.forEach { fileName ->
            val safeFileName = fileName.toSafeAssetFileName()
            val assetFile = "${model.assetPath.trimEnd('/')}/$safeFileName"
            if (!assetSource.exists(assetFile)) {
                throw IllegalStateException("MODEL_NOT_FOUND: missing $safeFileName for ${model.id}")
            }
            val targetFile = File(targetDir, safeFileName).canonicalFile
            ensureInsideCache(targetFile)
            // Native ncnn expects normal file paths, so APK assets are copied into app cache first.
            assetSource.open(assetFile).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        return targetDir
    }

    private fun ensureInsideCache(file: File) {
        val root = cacheRoot.canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
            "Refusing model cache path outside app cache: ${file.path}"
        }
    }

    private fun String.toSafeAssetFileName(): String {
        require(isNotBlank() && !contains('/') && !contains('\\') && this != "." && this != "..") {
            "Unsafe model asset file name: $this"
        }
        return this
    }

    companion object {
        fun fromContext(context: Context): ModelAssetRepository {
            return ModelAssetRepository(
                assetSource = AndroidModelAssetSource(context),
                cacheRoot = context.cacheDir
            )
        }
    }
}
