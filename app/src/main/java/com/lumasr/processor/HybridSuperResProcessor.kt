package com.lumasr.processor

import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult

class HybridSuperResProcessor(
    private val nativeProcessor: SuperResProcessor = NativeSuperResProcessor(),
    private val fallbackProcessor: SuperResProcessor = MockSuperResProcessor(),
    private val nativeAvailable: () -> Boolean = { NativeSuperResProcessor.isAvailable() },
    private val allowMockFallback: Boolean = false
) : SuperResProcessor {
    override suspend fun process(
        params: UpscaleParams,
        onProgress: (UpscaleProgress) -> Unit
    ): UpscaleResult {
        return if (nativeAvailable()) {
            nativeProcessor.process(params, onProgress)
        } else if (allowMockFallback) {
            fallbackProcessor.process(params, onProgress).copy(
                message = "Mock preview complete. Native inference is unavailable."
            )
        } else {
            nativeProcessor.process(params, onProgress)
        }
    }

    override fun cancel(taskId: String) {
        if (nativeAvailable()) {
            nativeProcessor.cancel(taskId)
        }
        if (allowMockFallback) fallbackProcessor.cancel(taskId)
    }
}
