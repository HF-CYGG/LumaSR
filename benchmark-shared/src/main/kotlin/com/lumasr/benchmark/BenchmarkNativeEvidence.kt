package com.lumasr.benchmark

enum class BenchmarkNativeEvidenceMode {
    WHOLE_TASK,
    EXTREME_TILES
}

data class BenchmarkNativeEvidenceSpec(
    val scenarioId: String,
    val mode: BenchmarkNativeEvidenceMode,
    val expectedNativeModelKey: String,
    val transportMode: String = TRANSPORT_NOT_APPLICABLE,
    val expectedPipelinePassCount: Int = 0,
    val expectedPipelineModelKeys: List<String> = emptyList()
) {
    init {
        require(scenarioId.isNotBlank())
        require(expectedNativeModelKey.isNotBlank())
    }

    val requiresPipelineTransportEvidence: Boolean
        get() = transportMode != TRANSPORT_NOT_APPLICABLE

    companion object {
        const val TRANSPORT_NOT_APPLICABLE = "not-applicable"
    }
}

object BenchmarkNativeEvidence {
    private val nativeFieldPattern = Regex("([A-Za-z][A-Za-z0-9]*)=([^\\s]+)")
    private val supportedPipelineTransportModes = setOf("rgb_memory", "png_fallback")
    private val extremeConsistencyFields = listOf(
        "task",
        "modelKey",
        "model",
        "driverId",
        "driverVersion",
        "accel",
        "backend",
        "precision",
        "fallback",
        "requestedPipelineDepth",
        "pipelineDepth",
        "threads",
        "blocktimeMs",
        "postprocessThreads",
        "batch",
        "cacheCapacity",
        "tile"
    )
    private val extremeSumFields = listOf(
        "producerWaitMs",
        "consumerWaitMs",
        "overlapMs",
        "decodeMs",
        "modelLoadMs",
        "tileInputMs",
        "tileExtractMs",
        "tileCopyMs",
        "saveMs",
        "preprocessMs",
        "uploadMs",
        "inferenceMs",
        "downloadMs",
        "postprocessMs",
        "encodeMs",
        "totalMs"
    )
    private val extremeMaxFields = listOf("peakOccupiedSlots", "peakSlotBytes")
    private val requiredFields = listOf(
        "task",
        "modelKey",
        "driverId",
        "driverVersion",
        "region",
        "retry",
        "backend",
        "precision",
        "requestedPipelineDepth",
        "pipelineDepth",
        "peakOccupiedSlots",
        "peakSlotBytes",
        "producerWaitMs",
        "consumerWaitMs",
        "overlapMs",
        "postprocessThreads",
        "totalMs"
    )

    fun parseFields(line: String): Map<String, String> = nativeFieldPattern.findAll(line)
        .associate { match -> match.groupValues[1] to match.groupValues[2] }

    fun validate(
        lines: List<String>,
        spec: BenchmarkNativeEvidenceSpec
    ): Map<String, String> {
        val evidence = lines.map(::parseFields)
        val taskIds = evidence.mapNotNull { fields -> fields["task"] }
            .filter(String::isNotBlank)
            .distinct()
        check(taskIds.size == 1 && evidence.all { fields -> fields["task"] == taskIds.single() }) {
            "${spec.scenarioId} expected one native task after log reset, got $taskIds"
        }
        if (spec.mode == BenchmarkNativeEvidenceMode.EXTREME_TILES) {
            check(evidence.all { fields -> fields["retry"] == "0" }) {
                "${spec.scenarioId} extreme evidence contains a retry and is not a clean measured run"
            }
        }
        val successfulEvidence = evidence.filter { fields -> fields["retry"] == "0" }
        val finalEvidence = when (spec.mode) {
            BenchmarkNativeEvidenceMode.WHOLE_TASK -> successfulEvidence.filter { fields ->
                fields["region"] == "-1"
            }
            BenchmarkNativeEvidenceMode.EXTREME_TILES -> successfulEvidence.filter { fields ->
                fields["region"]?.toIntOrNull()?.let { it >= 0 } == true
            }
        }
        when (spec.mode) {
            BenchmarkNativeEvidenceMode.WHOLE_TASK -> validateWholeTaskCardinality(
                allLineCount = lines.size,
                finalEvidence = finalEvidence,
                spec = spec
            )
            BenchmarkNativeEvidenceMode.EXTREME_TILES -> validateExtremeRegions(
                finalEvidence = finalEvidence,
                spec = spec
            )
        }
        validateModelAndPipelinePasses(finalEvidence, spec)
        finalEvidence.forEach { fields ->
            requiredFields.forEach { field ->
                val value = fields[field]
                check(!value.isNullOrBlank() && value != "missing" && value != "unknown") {
                    "${spec.scenarioId} native evidence is missing required field '$field'"
                }
            }
        }
        return when (spec.mode) {
            BenchmarkNativeEvidenceMode.WHOLE_TASK -> finalEvidence.last() +
                ("nativeRecordCount" to finalEvidence.size.toString())
            BenchmarkNativeEvidenceMode.EXTREME_TILES -> aggregateExtremeEvidence(finalEvidence)
        }
    }

    private fun validateWholeTaskCardinality(
        allLineCount: Int,
        finalEvidence: List<Map<String, String>>,
        spec: BenchmarkNativeEvidenceSpec
    ) {
        when {
            !spec.requiresPipelineTransportEvidence -> check(finalEvidence.size == 1) {
                "${spec.scenarioId} expected exactly one final region=-1 retry=0 record, " +
                    "got ${finalEvidence.size} from $allLineCount lines"
            }
            spec.transportMode == "rgb_memory" -> check(finalEvidence.size == 1) {
                "${spec.scenarioId} rgb_memory must publish one final native record, " +
                    "got ${finalEvidence.size}"
            }
            spec.transportMode == "png_fallback" -> check(
                finalEvidence.size == spec.expectedPipelinePassCount
            ) {
                "${spec.scenarioId} png_fallback expected ${spec.expectedPipelinePassCount} " +
                    "pass records, got ${finalEvidence.size}"
            }
            else -> error(
                "${spec.scenarioId} has unsupported pipeline transportMode=${spec.transportMode}"
            )
        }
    }

    private fun validateExtremeRegions(
        finalEvidence: List<Map<String, String>>,
        spec: BenchmarkNativeEvidenceSpec
    ) {
        val regionRecords = finalEvidence.map { fields -> fields.getValue("region").toInt() }
        val regionIndexes = regionRecords.distinct().sorted()
        check(
            regionRecords.size == regionIndexes.size &&
                regionIndexes.size >= 2 &&
                regionIndexes == (0..regionIndexes.last()).toList()
        ) {
            "${spec.scenarioId} expected contiguous extreme tile evidence, got $regionIndexes"
        }
        extremeConsistencyFields.forEach { field ->
            val values = finalEvidence.map { fields -> fields[field] }.distinct()
            check(values.size == 1 && values.single() != null) {
                "${spec.scenarioId} extreme regions disagree on $field: $values"
            }
        }
    }

    private fun validateModelAndPipelinePasses(
        finalEvidence: List<Map<String, String>>,
        spec: BenchmarkNativeEvidenceSpec
    ) {
        check(finalEvidence.last()["modelKey"] == spec.expectedNativeModelKey) {
            "${spec.scenarioId} final native record was not ${spec.expectedNativeModelKey}"
        }
        if (!spec.requiresPipelineTransportEvidence) return
        check(spec.transportMode in supportedPipelineTransportModes) {
            "${spec.scenarioId} emitted unsupported transportMode=${spec.transportMode}"
        }
        if (spec.transportMode == "png_fallback") {
            val modelKeys = finalEvidence.map { fields -> fields["modelKey"] }
            check(modelKeys == spec.expectedPipelineModelKeys) {
                "${spec.scenarioId} png_fallback pass order changed: $modelKeys"
            }
        }
    }

    private fun aggregateExtremeEvidence(
        finalEvidence: List<Map<String, String>>
    ): Map<String, String> {
        val aggregate = finalEvidence.last().toMutableMap()
        extremeSumFields.forEach { field ->
            aggregate[field] = finalEvidence.fold(0L) { total, fields ->
                val value = fields[field]?.toLongOrNull()
                    ?: error("Extreme evidence field '$field' is not numeric")
                Math.addExact(total, value)
            }.toString()
        }
        extremeMaxFields.forEach { field ->
            aggregate[field] = finalEvidence.maxOf { fields ->
                fields[field]?.toLongOrNull()
                    ?: error("Extreme evidence field '$field' is not numeric")
            }.toString()
        }
        aggregate["nativeRecordCount"] = finalEvidence.size.toString()
        return aggregate
    }
}
