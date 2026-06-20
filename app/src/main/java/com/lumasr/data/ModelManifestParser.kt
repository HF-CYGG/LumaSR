package com.lumasr.data

import com.lumasr.domain.ModelManifest
import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ModelManifestParser {
    fun parse(rawJson: String): ModelManifest {
        val root = Json.parseToJsonElement(rawJson).jsonObject
        val models = root.getValue("models").jsonArray.map { element ->
            val item = element.jsonObject
            ModelPack(
                id = item.string("id"),
                displayName = item.string("displayName"),
                engine = SuperResEngine.valueOf(item.string("engine")),
                modelDir = item.string("modelDir"),
                assetPath = item.optionalString("assetPath") ?: item.string("modelDir"),
                modelFileBase = item.optionalString("modelFileBase"),
                isBuiltIn = item.optionalBoolean("isBuiltIn") ?: false,
                requiredFiles = item.optionalStringList("requiredFiles").orEmpty(),
                assetBytes = item.optionalLong("assetBytes"),
                description = item.string("description"),
                scenes = item.stringList("scene"),
                scales = item.intList("scales"),
                denoise = item.intList("denoise"),
                supportsTta = item.getValue("supportsTta").jsonPrimitive.content.toBoolean(),
                defaultScale = item.getValue("defaultScale").jsonPrimitive.int,
                defaultNoise = item.getValue("defaultNoise").jsonPrimitive.int,
                speedLevel = item.string("speedLevel"),
                qualityLevel = item.string("qualityLevel")
            )
        }
        return ModelManifest(
            version = root.getValue("version").jsonPrimitive.int,
            models = models
        )
    }

    private fun Map<String, JsonElement>.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun Map<String, JsonElement>.optionalString(key: String): String? =
        get(key)?.jsonPrimitive?.content

    private fun Map<String, JsonElement>.optionalBoolean(key: String): Boolean? =
        get(key)?.jsonPrimitive?.boolean

    private fun Map<String, JsonElement>.optionalLong(key: String): Long? =
        get(key)?.jsonPrimitive?.long

    private fun Map<String, JsonElement>.stringList(key: String): List<String> =
        getValue(key).jsonArrayContent().map { it.jsonPrimitive.content }

    private fun Map<String, JsonElement>.optionalStringList(key: String): List<String>? =
        get(key)?.jsonArrayContent()?.map { it.jsonPrimitive.content }

    private fun Map<String, JsonElement>.intList(key: String): List<Int> =
        getValue(key).jsonArrayContent().map { it.jsonPrimitive.int }

    private fun JsonElement.jsonArrayContent(): JsonArray = jsonArray
}
