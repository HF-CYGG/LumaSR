package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtremeTileSchedulerTest {
    @Test
    fun schedulesTilesInOutputRowMajorOrder() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 1300,
            imageHeight = 900,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN
        )
        val shuffled = plan.copy(tiles = plan.tiles.reversed())

        val jobs = ExtremeTileScheduler.schedule(
            plan = shuffled,
            initialAccelerationMode = AccelerationMode.VULKAN
        )

        assertEquals(plan.tiles.map { it.index }, jobs.map { it.tileSpec.index })
        assertEquals(plan.tiles.map { it.outputY to it.outputX }, jobs.map { it.tileSpec.outputY to it.tileSpec.outputX })
        assertEquals(AccelerationMode.VULKAN, jobs.first().accelerationMode)
        assertEquals(ExtremeTileJobStatus.PENDING, jobs.first().status)
    }

    @Test
    fun retryJobKeepsTileAndMovesToCpuAttempt() {
        val tile = ExtremeExportPlanner.plan(
            imageWidth = 512,
            imageHeight = 512,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN
        ).tiles.single()
        val job = ExtremeTileJob(
            tileSpec = tile,
            attempt = 0,
            accelerationMode = AccelerationMode.VULKAN,
            status = ExtremeTileJobStatus.RUNNING
        )

        val retry = job.asCpuRetry()

        assertEquals(tile, retry.tileSpec)
        assertEquals(1, retry.attempt)
        assertEquals(AccelerationMode.CPU, retry.accelerationMode)
        assertEquals(ExtremeTileJobStatus.PENDING, retry.status)
    }
}
