package com.lumasr.domain

data class LatestRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val digest: String?,
    val originalDownloadUrl: String,
    val acceleratedDownloadUrl: String
)

data class AvailableUpdate(
    val version: AppVersion,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val digest: String?,
    val originalDownloadUrl: String,
    val acceleratedDownloadUrl: String
)

interface LatestReleaseSource {
    suspend fun latestRelease(): LatestRelease
}

sealed interface UpdateCheckResult {
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class UpToDate(val installedVersion: AppVersion) : UpdateCheckResult
}

class CheckForUpdateUseCase(
    private val source: LatestReleaseSource
) {
    suspend fun check(installedVersion: String): UpdateCheckResult {
        val installed = requireNotNull(AppVersion.parse(installedVersion)) {
            "Installed version is not a supported numeric version."
        }
        val release = source.latestRelease()
        val remote = requireNotNull(release.resolveVersion()) {
            "Latest release tag or rolling release title is not a supported numeric version."
        }
        if (remote <= installed) {
            return UpdateCheckResult.UpToDate(installed)
        }
        return UpdateCheckResult.Available(
            AvailableUpdate(
                version = remote,
                title = release.title,
                notes = release.notes,
                pageUrl = release.pageUrl,
                assetName = release.assetName,
                assetSizeBytes = release.assetSizeBytes,
                digest = release.digest,
                originalDownloadUrl = release.originalDownloadUrl,
                acceleratedDownloadUrl = release.acceleratedDownloadUrl
            )
        )
    }

    private companion object {
        const val ROLLING_RELEASE_TAG = "release"
        val ROLLING_RELEASE_TITLE_PATTERN = Regex(
            pattern = "^(?:LumaSR\\s+)?([vV]?[0-9]+(?:\\.[0-9]+){1,3})$",
            option = RegexOption.IGNORE_CASE
        )
    }

    private fun LatestRelease.resolveVersion(): AppVersion? {
        AppVersion.parse(tagName)?.let { return it }
        if (tagName != ROLLING_RELEASE_TAG) return null
        val match = ROLLING_RELEASE_TITLE_PATTERN.matchEntire(title.trim()) ?: return null
        return AppVersion.parse(match.groupValues[1])
    }
}
