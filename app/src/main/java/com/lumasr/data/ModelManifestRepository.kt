package com.lumasr.data

import android.content.Context
import com.lumasr.domain.ModelManifest

class ModelManifestRepository(
    private val context: Context
) {
    fun loadManifest(): ModelManifest {
        val rawJson = context.assets.open(MODEL_MANIFEST_FILE).bufferedReader().use { it.readText() }
        return ModelManifestParser.parse(rawJson)
    }

    private companion object {
        const val MODEL_MANIFEST_FILE = "model_manifest.json"
    }
}
