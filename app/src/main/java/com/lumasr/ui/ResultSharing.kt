package com.lumasr.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

internal enum class ResultShareMode {
    SINGLE,
    MULTIPLE
}

internal data class ResultShareRequest(
    val mode: ResultShareMode,
    val uriStrings: List<String>,
    val mimeType: String
)

internal fun resultShareRequest(uriStrings: List<String>): ResultShareRequest? {
    val normalizedUris = uriStrings
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (normalizedUris.isEmpty()) return null

    return ResultShareRequest(
        mode = if (normalizedUris.size == 1) ResultShareMode.SINGLE else ResultShareMode.MULTIPLE,
        uriStrings = normalizedUris,
        mimeType = "image/png"
    )
}

internal fun Context.launchResultShare(uriStrings: List<String>) {
    val request = resultShareRequest(uriStrings) ?: return
    val uris = request.uriStrings.map(Uri::parse)
    val shareIntent = when (request.mode) {
        ResultShareMode.SINGLE -> Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        }

        ResultShareMode.MULTIPLE -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }.apply {
        type = request.mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, "LumaSR result", uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }
    }

    startActivity(Intent.createChooser(shareIntent, "分享 LumaSR 结果"))
}
