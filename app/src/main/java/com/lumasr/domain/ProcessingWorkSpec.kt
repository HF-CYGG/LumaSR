package com.lumasr.domain

import java.io.File

data class ProcessingWorkSpec private constructor(
    val modelId: String,
    val params: UpscaleParams,
    val outputPixels: Long?
) {
    val taskId: String
        get() = params.taskId

    init {
        require(modelId.isNotBlank()) { "Model id must not be blank" }
        require(taskId.isNotBlank()) { "Task id must not be blank" }
        require(outputPixels == null || outputPixels >= 0L) {
            "Output pixels must be non-negative"
        }
        validateParams(params)
    }

    fun toUpscaleParams(): UpscaleParams = params

    companion object {
        fun fromParams(
            modelId: String,
            params: UpscaleParams,
            outputPixels: Long? = null
        ): ProcessingWorkSpec {
            return ProcessingWorkSpec(
                modelId = modelId,
                params = params,
                outputPixels = outputPixels
            )
        }

        private fun validateParams(params: UpscaleParams) {
            requireStableLocalPath(params.inputPath, "Input path")
            requireStableLocalPath(params.outputPath, "Output path")
            params.pipelinePasses.forEach { pass ->
                require(pass.taskId == params.taskId) { "Pipeline pass task id must match its owner" }
                validateParams(pass)
            }
        }

        private fun requireStableLocalPath(path: String, label: String) {
            require(path.isNotBlank()) { "$label must not be blank" }
            require(!path.contains("://")) { "$label must be a local file, not a URI" }
            require(File(path).isAbsolute) { "$label must be absolute and stable" }
        }
    }
}

interface ProcessingWorkSpecRepository {
    suspend fun save(workSpec: ProcessingWorkSpec)

    suspend fun find(taskId: String): ProcessingWorkSpec?

    suspend fun delete(taskId: String): Boolean
}
