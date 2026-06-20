package com.lumasr.domain

enum class UpscaleErrorCode {
    INPUT_NOT_FOUND,
    INPUT_UNSUPPORTED,
    IMAGE_TOO_LARGE,
    MODEL_NOT_FOUND,
    MODEL_CHECKSUM_FAILED,
    VULKAN_NOT_AVAILABLE,
    VULKAN_RUNTIME_FAILED,
    OUT_OF_MEMORY,
    OUTPUT_WRITE_FAILED,
    TILE_OUTPUT_MISMATCH,
    NATIVE_UNAVAILABLE,
    CANCELLED,
    UNKNOWN
}

object UpscaleErrorMapper {
    fun userMessage(code: UpscaleErrorCode): String {
        return when (code) {
            UpscaleErrorCode.INPUT_NOT_FOUND -> "Cannot read this image. Please choose it again."
            UpscaleErrorCode.INPUT_UNSUPPORTED -> "This image format is not supported yet."
            UpscaleErrorCode.IMAGE_TOO_LARGE -> "The image is too large. Lower scale or tile size."
            UpscaleErrorCode.MODEL_NOT_FOUND -> "The selected model files are missing."
            UpscaleErrorCode.MODEL_CHECKSUM_FAILED -> "The selected model files failed validation."
            UpscaleErrorCode.VULKAN_NOT_AVAILABLE -> "GPU acceleration is unavailable. Switched to CPU retry."
            UpscaleErrorCode.VULKAN_RUNTIME_FAILED -> "GPU acceleration failed. Switched to CPU retry."
            UpscaleErrorCode.OUT_OF_MEMORY -> "Memory is insufficient. Try a smaller image or lower scale."
            UpscaleErrorCode.OUTPUT_WRITE_FAILED -> "Output failed. Check storage space and retry."
            UpscaleErrorCode.TILE_OUTPUT_MISMATCH -> "Model output size was inconsistent. Processing stopped to avoid striped exports."
            UpscaleErrorCode.NATIVE_UNAVAILABLE -> "Native inference is not installed. Install the native runtime and built-in models."
            UpscaleErrorCode.CANCELLED -> "Processing was cancelled."
            UpscaleErrorCode.UNKNOWN -> "Processing failed for an unknown reason."
        }
    }
}
