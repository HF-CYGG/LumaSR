package com.lumasr.data

import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationFallbackReason
import com.lumasr.domain.CalibrationMeasuredBackend
import com.lumasr.domain.CalibrationPerformanceGrade
import com.lumasr.domain.CalibrationProgressEventType
import com.lumasr.domain.CalibrationReport
import com.lumasr.domain.CalibrationReportEvent
import com.lumasr.domain.CalibrationReportOutcome
import com.lumasr.domain.CalibrationTierOutcome
import com.lumasr.domain.ResolutionTier
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class FileCalibrationReportRepository(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun save(report: CalibrationReport) = withContext(ioDispatcher) {
        PROCESS_MUTEX.withLock {
            val sanitized = report.sanitized()
            val bytes = CalibrationReportJson.encode(sanitized).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_REPORT_BYTES) { "Calibration report exceeds 1 MiB" }
            ensureDirectory()
            val destination = reportFile(sanitized)
            val temporary = File.createTempFile("calibration-report-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                moveReportReplacing(temporary, destination)
                pruneLocked()
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun list(): List<CalibrationReport> = withContext(ioDispatcher) {
        PROCESS_MUTEX.withLock { readAllLocked() }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create calibration report directory"
        }
    }

    private fun pruneLocked() {
        readAllLocked()
            .drop(MAX_REPORT_COUNT)
            .forEach { report -> reportFile(report).delete() }
    }

    private fun readAllLocked(): List<CalibrationReport> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && REPORT_FILE.matches(it.name) }
            .mapNotNull { file ->
                try {
                    check(file.length() in 1..MAX_REPORT_BYTES.toLong()) {
                        "Calibration report has an invalid size"
                    }
                    CalibrationReportJson.decode(file.readText(Charsets.UTF_8)).sanitized()
                } catch (_: Exception) {
                    quarantine(file)
                    null
                }
            }
            .distinctBy(CalibrationReport::id)
            .sortedWith(
                compareByDescending<CalibrationReport> { it.finishedAtEpochMs }
                    .thenByDescending { it.id }
            )
    }

    private fun quarantine(file: File) {
        val target = File(directory, "${file.name}.${System.currentTimeMillis()}.corrupt")
        runCatching { moveReportReplacing(file, target) }
            .onFailure { file.delete() }
    }

    private fun reportFile(report: CalibrationReport): File = File(
        directory,
        "calibration-report-${report.finishedAtEpochMs}-${report.id}.json"
    )

    private fun CalibrationReport.sanitized(): CalibrationReport = copy(
        modelId = sanitizeReportText(modelId),
        summary = sanitizeReportText(summary),
        events = events.map { event ->
            event.copy(
                title = sanitizeReportText(event.title),
                message = sanitizeReportText(event.message)
            )
        }
    )

    companion object {
        const val MAX_REPORT_COUNT = 5
        const val MAX_REPORT_BYTES = 1_024 * 1_024
        private val PROCESS_MUTEX = Mutex()
        private val REPORT_FILE = Regex("calibration-report-[0-9]+-[A-Za-z0-9._-]{1,64}\\.json")
        private val WINDOWS_ABSOLUTE_PATH = Regex("(?i)\\b[a-z]:[\\\\/][^\\s·|]+")
        private val UNIX_ABSOLUTE_PATH = Regex("(?<![A-Za-z0-9:])/(?!/)[^\\s·|]+(?:/[^\\s·|]+)+")

        internal fun sanitizeReportText(value: String): String = WINDOWS_ABSOLUTE_PATH
            .replace(value, "<redacted-path>")
            .let { UNIX_ABSOLUTE_PATH.replace(it, "<redacted-path>") }
    }
}

private object CalibrationReportJson {
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1

    fun encode(report: CalibrationReport): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", report.id)
        put("mode", report.mode.name)
        put("outcome", report.outcome.name)
        put("startedAtEpochMs", report.startedAtEpochMs)
        put("finishedAtEpochMs", report.finishedAtEpochMs)
        put("elapsedMs", report.elapsedMs)
        put("acceleratorCalibrationKey", report.acceleratorCalibrationKey)
        put("modelId", report.modelId)
        put("summary", report.summary)
        put("overallGrade", report.overallGrade?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("tierOutcomes", buildJsonArray {
            report.tierOutcomes.forEach { outcome ->
                add(buildJsonObject {
                    put("resolutionTier", outcome.resolutionTier.name)
                    put("grade", outcome.grade.name)
                    put("backend", outcome.backend.name)
                    put("baselineDurationNanos", outcome.baselineDurationNanos)
                    put(
                        "selectedDurationNanos",
                        outcome.selectedDurationNanos?.let(::JsonPrimitive) ?: JsonNull
                    )
                    put("speedupPercent", outcome.speedupPercent?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "fallbackReason",
                        outcome.fallbackReason?.name?.let(::JsonPrimitive) ?: JsonNull
                    )
                })
            }
        })
        put("events", buildJsonArray {
            report.events.forEach { event ->
                add(buildJsonObject {
                    put("elapsedMs", event.elapsedMs)
                    put("type", event.type.name)
                    put("title", event.title)
                    put("message", event.message)
                    put("stepIndex", event.stepIndex)
                    put("totalSteps", event.totalSteps)
                    put("completedWorkUnits", event.completedWorkUnits)
                    put("totalWorkUnits", event.totalWorkUnits)
                })
            }
        })
    }.toString()

    fun decode(value: String): CalibrationReport {
        val record = Json.parseToJsonElement(value).jsonObject
        val schemaVersion = record.requiredLong("schemaVersion").toIntExact("schemaVersion")
        check(schemaVersion == SCHEMA_VERSION || schemaVersion == LEGACY_SCHEMA_VERSION) {
            "Unsupported calibration report schema"
        }
        return CalibrationReport(
            id = record.requiredString("id"),
            mode = CalibrationMode.valueOf(record.requiredString("mode")),
            outcome = CalibrationReportOutcome.valueOf(record.requiredString("outcome")),
            startedAtEpochMs = record.requiredLong("startedAtEpochMs"),
            finishedAtEpochMs = record.requiredLong("finishedAtEpochMs"),
            elapsedMs = record.requiredLong("elapsedMs"),
            acceleratorCalibrationKey = record["acceleratorCalibrationKey"]
                ?.jsonPrimitive
                ?.contentOrNull,
            modelId = record.requiredString("modelId"),
            summary = record.requiredString("summary"),
            overallGrade = if (schemaVersion >= 2) {
                record["overallGrade"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
                    ?.let(CalibrationPerformanceGrade::valueOf)
            } else {
                null
            },
            tierOutcomes = if (schemaVersion >= 2) {
                record.getValue("tierOutcomes").jsonArray.map { element ->
                    val outcome = element.jsonObject
                    CalibrationTierOutcome(
                        resolutionTier = ResolutionTier.valueOf(
                            outcome.requiredString("resolutionTier")
                        ),
                        grade = CalibrationPerformanceGrade.valueOf(outcome.requiredString("grade")),
                        backend = CalibrationMeasuredBackend.valueOf(
                            outcome.requiredString("backend")
                        ),
                        baselineDurationNanos = outcome.requiredLong("baselineDurationNanos"),
                        selectedDurationNanos = outcome.optionalLong("selectedDurationNanos"),
                        speedupPercent = outcome.optionalDouble("speedupPercent"),
                        fallbackReason = outcome.optionalString("fallbackReason")?.let(
                            CalibrationFallbackReason::valueOf
                        )
                    )
                }
            } else {
                emptyList()
            },
            events = record.getValue("events").jsonArray.map { element ->
                val event = element.jsonObject
                CalibrationReportEvent(
                    elapsedMs = event.requiredLong("elapsedMs"),
                    type = CalibrationProgressEventType.valueOf(event.requiredString("type")),
                    title = event.requiredString("title"),
                    message = event.requiredString("message"),
                    stepIndex = event.requiredLong("stepIndex").toIntExact("stepIndex"),
                    totalSteps = event.requiredLong("totalSteps").toIntExact("totalSteps"),
                    completedWorkUnits = event.requiredLong("completedWorkUnits"),
                    totalWorkUnits = event.requiredLong("totalWorkUnits")
                )
            }
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive
        .also { check(it.isString) { "$key must be a JSON string" } }
        .content

    private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive
        .also { check(!it.isString) { "$key must be a JSON number" } }
        .long

    private fun JsonObject.optionalString(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.optionalLong(key: String): Long? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.long

    private fun JsonObject.optionalDouble(key: String): Double? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.double?.also {
            check(it.isFinite()) { "$key must be finite" }
        }

    private fun Long.toIntExact(label: String): Int {
        check(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$label is out of range" }
        return toInt()
    }
}

private fun moveReportReplacing(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
