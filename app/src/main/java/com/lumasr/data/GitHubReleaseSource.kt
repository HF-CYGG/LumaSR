package com.lumasr.data

import com.lumasr.domain.LatestRelease
import com.lumasr.domain.LatestReleaseSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class NoPublishedReleaseException : IOException("No published GitHub release is available.")

class GitHubReleaseSource(
    private val userAgent: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : LatestReleaseSource {
    override suspend fun latestRelease(): LatestRelease = withContext(ioDispatcher) {
        val connection = connectionFactory(URL(LATEST_RELEASE_ENDPOINT))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connection.setRequestProperty("User-Agent", userAgent)

            when (val statusCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    GitHubReleaseParser.parse(payload)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> throw NoPublishedReleaseException()
                HTTP_TOO_MANY_REQUESTS, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw IOException("GitHub release service is temporarily unavailable.")
                else -> throw IOException("GitHub release service returned HTTP $statusCode.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE_ENDPOINT =
            "https://api.github.com/repos/HF-CYGG/LumaSR/releases/latest"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

object GitHubReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): LatestRelease {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root.boolean("draft") != true && root.boolean("prerelease") != true) {
            "Latest release must be a published full release."
        }
        val tagName = root.requiredString("tag_name")
        val pageUrl = root.requiredString("html_url")
        require(isValidatedGitHubReleasePageUrl(pageUrl)) {
            "Latest release page URL is outside the LumaSR GitHub repository."
        }
        val candidates = root["assets"]?.jsonArray.orEmpty()
            .mapNotNull { element -> element.jsonObject.toApkCandidateOrNull() }
        val canonical = candidates.filter { it.name == CANONICAL_APK_NAME }
        val selected = when {
            canonical.size == 1 -> canonical.single()
            canonical.size > 1 -> null
            candidates.size == 1 -> candidates.single()
            else -> null
        } ?: throw IllegalArgumentException(
            "Latest release does not contain one unambiguous APK asset."
        )

        return LatestRelease(
            tagName = tagName,
            title = root.optionalString("name").orEmpty().ifBlank { tagName },
            notes = root.optionalString("body").orEmpty(),
            pageUrl = pageUrl,
            assetName = selected.name,
            assetSizeBytes = selected.sizeBytes,
            digest = selected.digest,
            originalDownloadUrl = selected.downloadUrl,
            acceleratedDownloadUrl = buildAcceleratedDownloadUrl(selected.downloadUrl)
        )
    }

    private fun JsonObject.toApkCandidateOrNull(): ApkCandidate? {
        if (optionalString("state") != "uploaded") return null
        val name = optionalString("name") ?: return null
        val sizeBytes = this["size"]?.jsonPrimitive?.longOrNull ?: return null
        if (sizeBytes <= 0L || !name.endsWith(".apk", ignoreCase = true)) return null
        val downloadUrl = optionalString("browser_download_url") ?: return null
        if (!isValidatedGitHubApkUrl(downloadUrl)) return null
        return ApkCandidate(
            name = name,
            sizeBytes = sizeBytes,
            digest = optionalString("digest"),
            downloadUrl = downloadUrl
        )
    }

    private data class ApkCandidate(
        val name: String,
        val sizeBytes: Long,
        val digest: String?,
        val downloadUrl: String
    )

    private const val CANONICAL_APK_NAME = "LumaSR.apk"
}

fun buildAcceleratedDownloadUrl(originalUrl: String): String {
    require(isValidatedGitHubApkUrl(originalUrl)) {
        "Only LumaSR GitHub Release APK URLs can use the acceleration service."
    }
    return "https://gh.jasonzeng.dev/$originalUrl"
}

private fun isValidatedGitHubApkUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val path = uri.path ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        path.startsWith(LUMASR_RELEASE_DOWNLOAD_PREFIX) &&
        path.endsWith(".apk", ignoreCase = true) &&
        path.split('/').none { it == ".." }
}

private fun isValidatedGitHubReleasePageUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val path = uri.path ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        path.startsWith("/HF-CYGG/LumaSR/releases/")
}

private fun JsonObject.requiredString(key: String): String =
    requireNotNull(optionalString(key)) { "GitHub release is missing $key." }

private fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

private const val LUMASR_RELEASE_DOWNLOAD_PREFIX =
    "/HF-CYGG/LumaSR/releases/download/"
