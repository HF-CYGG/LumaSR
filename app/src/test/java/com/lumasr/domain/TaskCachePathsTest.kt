package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TaskCachePathsTest {
    @Test
    fun createsStableTaskCachePaths() {
        val paths = TaskCachePaths.create(File("cache"), "task-1", "image/png")

        assertEquals(File("cache/upscale/task-1/input.png"), paths.inputFile)
        assertEquals(File("cache/upscale/task-1/output.png"), paths.outputFile)
        assertEquals(File("cache/upscale/task-1/preview.jpg"), paths.previewFile)
        assertEquals(File("cache/upscale/task-1/log.txt"), paths.logFile)
    }
}
