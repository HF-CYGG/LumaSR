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
        if (release.tagName == LEGACY_RELEASE_TAG) {
            return UpdateCheckResult.UpToDate(installed)
        }
        val remote = requireNotNull(AppVersion.parse(release.tagName)) {
            "Latest release tag is not a supported numeric version."
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
        const val LEGACY_RELEASE_TAG = "release"
    }
}
