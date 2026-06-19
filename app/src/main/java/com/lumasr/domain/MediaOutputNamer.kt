package com.lumasr.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MediaOutputNamer {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneId.of("Asia/Shanghai"))

    fun create(
        timestampMillis: Long,
        modelName: String,
        scale: Int,
        format: OutputFormat
    ): String {
        val safeModelName = modelName
            .trim()
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "Model" }
        val extension = when (format) {
            OutputFormat.PNG -> "png"
            OutputFormat.JPEG -> "jpg"
            OutputFormat.WEBP -> "webp"
        }
        return "LocalSR_${formatter.format(Instant.ofEpochMilli(timestampMillis))}_${safeModelName}_${scale}x.$extension"
    }
}
