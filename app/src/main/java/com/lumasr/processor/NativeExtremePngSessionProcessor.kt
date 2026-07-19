package com.lumasr.processor

import android.graphics.Bitmap
import com.lumasr.domain.ExtremeExportPlan
import com.lumasr.domain.ExtremeExportTileSpec
import com.lumasr.domain.RuntimeInferenceControlProvider
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleResult

/**
 * Optional native capability for a task-scoped, strictly ordered Extreme PNG writer.
 * A [begin] failure means the caller may safely fall back to RAW tiles; cancellation must be rethrown.
 */
interface NativeExtremePngSessionProcessor {
    fun begin(params: UpscaleParams, plan: ExtremeExportPlan): NativeExtremePngSession?
}

/**
 * Owns the unpublished output until [finish] succeeds. Calls are stateful: process only before finish
 * or abort, finish only once after successful processing, and abort/close are idempotent no-ops after
 * finish. [processBitmapTile] reports progress synchronously: it must not invoke [onProgress] after it
 * returns. Once [finish] publishes output, it must not emit any progress callback.
 */
interface NativeExtremePngSession : AutoCloseable {
    suspend fun processBitmapTile(
        params: UpscaleParams,
        bitmap: Bitmap?,
        tile: ExtremeExportTileSpec,
        onProgress: (UpscaleProgress) -> Unit,
        runtimeControlProvider: RuntimeInferenceControlProvider?
    ): UpscaleResult

    /** Publishes only after all accepted tiles; it must not report progress after publication. */
    fun finish(): NativeProcessCode

    fun abort()

    override fun close() {
        abort()
    }
}
