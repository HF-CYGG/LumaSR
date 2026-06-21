package com.lumasr.domain

data class ExtremeTileJob(
    val tileSpec: ExtremeExportTileSpec,
    val attempt: Int,
    val accelerationMode: AccelerationMode,
    val status: ExtremeTileJobStatus
) {
    fun asCpuRetry(): ExtremeTileJob = copy(
        attempt = attempt + 1,
        accelerationMode = AccelerationMode.CPU,
        status = ExtremeTileJobStatus.PENDING
    )
}

enum class ExtremeTileJobStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}

object ExtremeTileScheduler {
    fun schedule(
        plan: ExtremeExportPlan,
        initialAccelerationMode: AccelerationMode
    ): List<ExtremeTileJob> {
        return plan.tiles
            .sortedWith(compareBy<ExtremeExportTileSpec> { it.outputY }.thenBy { it.outputX })
            .map { tile ->
                ExtremeTileJob(
                    tileSpec = tile,
                    attempt = 0,
                    accelerationMode = initialAccelerationMode,
                    status = ExtremeTileJobStatus.PENDING
                )
            }
    }
}
