package com.lumasr.data

import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationProgressEventType
import com.lumasr.domain.CalibrationReport
import com.lumasr.domain.CalibrationReportEvent
import com.lumasr.domain.CalibrationReportOutcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCalibrationReportRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun keepsFiveNewestCompleteTerminalReports() = runBlocking {
        val root = temporaryFolder.newFolder("reports")
        val repository = FileCalibrationReportRepository(root)
        repeat(6) { index ->
            repository.save(report(index))
        }

        val reports = repository.list()

        assertEquals(listOf("report-5", "report-4", "report-3", "report-2", "report-1"), reports.map { it.id })
        assertTrue(reports.all { it.events.size == 2 })
        assertEquals(5, root.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun redactsAbsolutePathsWithoutDroppingCalibrationEvents() = runBlocking {
        val root = temporaryFolder.newFolder("redacted")
        val repository = FileCalibrationReportRepository(root)
        val report = report(1).copy(
            events = listOf(
                CalibrationReportEvent(
                    elapsedMs = 10L,
                    type = CalibrationProgressEventType.NOTE,
                    title = "探针",
                    message = "model=F:\\private\\models\\x.bin output=/storage/emulated/0/DCIM/x.png"
                )
            )
        )

        repository.save(report)

        val stored = repository.list().single()
        assertEquals(1, stored.events.size)
        assertTrue(stored.events.single().message.contains("<redacted-path>"))
        val json = root.listFiles().orEmpty().single { it.extension == "json" }.readText()
        assertFalse(json.contains("private"))
        assertFalse(json.contains("/storage/"))
    }

    @Test
    fun oversizedReportCannotReplaceExistingHistory() = runBlocking {
        val root = temporaryFolder.newFolder("oversized")
        val repository = FileCalibrationReportRepository(root)
        val existing = report(1)
        repository.save(existing)
        val oversized = report(2).copy(
            events = listOf(
                CalibrationReportEvent(
                    elapsedMs = 1L,
                    type = CalibrationProgressEventType.NOTE,
                    title = "oversized",
                    message = "x".repeat(1_100_000)
                )
            )
        )

        val failure = runCatching { repository.save(oversized) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf(existing), repository.list())
    }

    @Test
    fun corruptReportIsQuarantinedWithoutHidingValidHistory() = runBlocking {
        val root = temporaryFolder.newFolder("corrupt")
        val repository = FileCalibrationReportRepository(root)
        val valid = report(1)
        repository.save(valid)
        File(root, "calibration-report-2-corrupt.json").writeText("{bad-json")

        assertEquals(listOf(valid), repository.list())
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    private fun report(index: Int) = CalibrationReport(
        id = "report-$index",
        mode = if (index % 2 == 0) CalibrationMode.DEEP else CalibrationMode.QUICK,
        outcome = CalibrationReportOutcome.SUCCEEDED,
        startedAtEpochMs = index * 1_000L,
        finishedAtEpochMs = index * 1_000L + 500L,
        elapsedMs = 500L,
        acceleratorCalibrationKey = "adreno-capability-v1:${"a".repeat(64)}",
        modelId = "model-a",
        summary = "completed-$index",
        events = listOf(
            CalibrationReportEvent(
                elapsedMs = 0L,
                type = CalibrationProgressEventType.SESSION_STARTED,
                title = "start",
                message = "> mode"
            ),
            CalibrationReportEvent(
                elapsedMs = 500L,
                type = CalibrationProgressEventType.NOTE,
                title = "finish",
                message = "completed-$index"
            )
        )
    )
}
