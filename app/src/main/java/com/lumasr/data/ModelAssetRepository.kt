package com.lumasr.data

import android.content.Context
import android.util.Log
import com.lumasr.domain.ModelPack
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ModelAssetSource {
    fun open(path: String): InputStream
    fun exists(path: String): Boolean
    fun size(path: String): Long? = null
}

class AndroidModelAssetSource(
    private val context: Context
) : ModelAssetSource {
    override fun open(path: String): InputStream = context.assets.open(path)

    override fun exists(path: String): Boolean {
        return runCatching { context.assets.open(path).close() }.isSuccess
    }

    override fun size(path: String): Long? {
        return runCatching { context.assets.openFd(path).use { it.length } }.getOrNull()
            ?: runCatching {
                context.assets.open(path).use { input -> input.available().toLong() }
            }.getOrNull()
    }
}

data class ModelPreparationProgress(
    val modelId: String,
    val fileName: String?,
    val completedBytes: Long,
    val totalBytes: Long?,
    val completedFiles: Int,
    val totalFiles: Int
) {
    val fraction: Float
        get() = when {
            totalBytes != null && totalBytes > 0L -> completedBytes.toFloat() / totalBytes.toFloat()
            totalFiles > 0 -> completedFiles.toFloat() / totalFiles.toFloat()
            else -> 1f
        }.coerceIn(0f, 1f)
}

class ModelAssetRepository(
    private val assetSource: ModelAssetSource,
    private val cacheRoot: File,
    private val logger: (String) -> Unit = {}
) {
    fun prepareModel(
        model: ModelPack,
        requiredFiles: Collection<String> = model.requiredFiles,
        onProgress: (ModelPreparationProgress) -> Unit = {},
        cancellationCheck: () -> Unit = {}
    ): File {
        require(model.isBuiltIn) { "Model ${model.id} is not marked as built-in." }
        require(model.assetPath.isNotBlank()) { "Model ${model.id} has no assetPath." }
        require(requiredFiles.isNotEmpty()) { "Model ${model.id} has no required files." }

        val requestedFiles = requiredFiles.distinct()
        val unknownFiles = requestedFiles - model.requiredFiles.toSet()
        require(unknownFiles.isEmpty()) {
            "Model ${model.id} does not declare required files: ${unknownFiles.joinToString()}"
        }

        val targetDir = File(cacheRoot, "models/${model.id}").canonicalFile
        ensureInsideCache(targetDir)
        targetDir.mkdirs()

        cancellationCheck()
        logger("begin model=${model.id} files=${requestedFiles.size}")
        onProgress(
            ModelPreparationProgress(
                modelId = model.id,
                fileName = null,
                completedBytes = 0L,
                totalBytes = null,
                completedFiles = 0,
                totalFiles = requestedFiles.size
            )
        )

        val preparations = requestedFiles.map { fileName ->
            cancellationCheck()
            val safeFileName = fileName.toSafeAssetFileName()
            val assetFile = "${model.assetPath.trimEnd('/')}/$safeFileName"
            if (!assetSource.exists(assetFile)) {
                throw IllegalStateException("MODEL_NOT_FOUND: missing $safeFileName for ${model.id}")
            }
            val targetFile = File(targetDir, safeFileName).canonicalFile
            ensureInsideCache(targetFile)
            ModelFilePreparation(
                fileName = safeFileName,
                assetPath = assetFile,
                targetFile = targetFile,
                expectedSize = assetSource.size(assetFile)
            )
        }
        val totalBytes = preparations.map { it.expectedSize }.let { sizes ->
            if (sizes.all { it != null }) sizes.sumOf { requireNotNull(it) } else null
        }
        logger("metadata model=${model.id} totalBytes=${totalBytes ?: -1L}")
        var completedBytes = 0L
        var completedFiles = 0

        fun report(fileName: String? = null, inFlightBytes: Long = 0L) {
            onProgress(
                ModelPreparationProgress(
                    modelId = model.id,
                    fileName = fileName,
                    completedBytes = completedBytes + inFlightBytes,
                    totalBytes = totalBytes,
                    completedFiles = completedFiles,
                    totalFiles = preparations.size
                )
            )
        }

        report()
        preparations.forEach { preparation ->
            cancellationCheck()
            if (preparation.isCurrent()) {
                logger("cache-hit model=${model.id} file=${preparation.fileName}")
                completedBytes += preparation.expectedSize ?: preparation.targetFile.length()
                completedFiles += 1
                report(preparation.fileName)
                return@forEach
            }
            val copiedBytes = copyAssetAtomically(
                preparation = preparation,
                cancellationCheck = cancellationCheck,
                onBytesCopied = { copied -> report(preparation.fileName, copied) }
            )
            completedBytes += copiedBytes
            logger("copied model=${model.id} file=${preparation.fileName} bytes=$copiedBytes")
            completedFiles += 1
            report(preparation.fileName)
        }

        return targetDir
    }

    private fun copyAssetAtomically(
        preparation: ModelFilePreparation,
        cancellationCheck: () -> Unit,
        onBytesCopied: (Long) -> Unit
    ): Long {
        val temporaryFile = File.createTempFile(".${preparation.fileName}.", ".tmp", preparation.targetFile.parentFile)
            .canonicalFile
        ensureInsideCache(temporaryFile)
        var copiedBytes = 0L
        try {
            // Native ncnn expects normal file paths, so APK assets are copied into app cache first.
            assetSource.open(preparation.assetPath).use { input ->
                temporaryFile.outputStream().use { output ->
                    val buffer = ByteArray(MODEL_COPY_BUFFER_BYTES)
                    while (true) {
                        cancellationCheck()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        onBytesCopied(copiedBytes)
                    }
                }
            }
            cancellationCheck()
            preparation.expectedSize?.let { expectedSize ->
                check(copiedBytes == expectedSize) {
                    "MODEL_NOT_FOUND: incomplete ${preparation.fileName}; expected $expectedSize bytes, copied $copiedBytes"
                }
            }
            replaceFile(temporaryFile, preparation.targetFile)
            return copiedBytes
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
        private const val MODEL_COPY_BUFFER_BYTES = 256 * 1024

        fun fromContext(context: Context): ModelAssetRepository {
            return ModelAssetRepository(
                assetSource = AndroidModelAssetSource(context),
                cacheRoot = context.cacheDir,
                logger = { message -> Log.i(MODEL_PREPARATION_LOG_TAG, message) }
            )
        }

        private const val MODEL_PREPARATION_LOG_TAG = "LumaSRModelPrep"
    }
}

private data class ModelFilePreparation(
    val fileName: String,
    val assetPath: String,
    val targetFile: File,
    val expectedSize: Long?
) {
    fun isCurrent(): Boolean =
        expectedSize != null && targetFile.isFile && targetFile.length() == expectedSize
}
