package com.lumasr.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckForUpdateUseCaseTest {
    @Test
    fun returnsAvailableUpdateWithReleaseDownloadLinks() = runBlocking {
        val release = release(tagName = "v0.3.2")
        val result = CheckForUpdateUseCase(FakeLatestReleaseSource(release)).check("0.3.1")

        val available = result as UpdateCheckResult.Available
        assertEquals("0.3.2", available.update.version.toString())
        assertEquals(release.originalDownloadUrl, available.update.originalDownloadUrl)
        assertEquals(release.acceleratedDownloadUrl, available.update.acceleratedDownloadUrl)
    }

    @Test
    fun returnsUpToDateWhenRemoteIsEqualOrOlder() = runBlocking {
        val equal = CheckForUpdateUseCase(FakeLatestReleaseSource(release("v0.3.1"))).check("0.3.1")
        val older = CheckForUpdateUseCase(FakeLatestReleaseSource(release("v0.3.0"))).check("0.3.1")

        assertEquals("0.3.1", (equal as UpdateCheckResult.UpToDate).installedVersion.toString())
        assertEquals("0.3.1", (older as UpdateCheckResult.UpToDate).installedVersion.toString())
    }

    @Test
    fun readsVersionFromTitleForRollingReleaseTag() = runBlocking {
        val rollingRelease = release(
            tagName = "release",
            title = "v0.3.2",
            assetName = "LumaSR-v0.3.2.apk"
        )
        val result = CheckForUpdateUseCase(FakeLatestReleaseSource(rollingRelease)).check("0.3.1")

        val available = result as UpdateCheckResult.Available
        assertEquals("0.3.2", available.update.version.toString())
        assertEquals(
            "https://github.com/HF-CYGG/LumaSR/releases/download/release/LumaSR-v0.3.2.apk",
            available.update.originalDownloadUrl
        )
    }

    @Test
    fun acceptsExistingLumaSrTitleFormatForRollingReleaseTag() = runBlocking {
        val rollingRelease = release(
            tagName = "release",
            title = "LumaSR v0.3.1"
        )
        val result = CheckForUpdateUseCase(FakeLatestReleaseSource(rollingRelease)).check("0.3.1")

        assertEquals("0.3.1", (result as UpdateCheckResult.UpToDate).installedVersion.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRollingReleaseTitleWithoutExactVersion() = runBlocking<Unit> {
        val rollingRelease = release(
            tagName = "release",
            title = "LumaSR v0.3.2 hotfix"
        )

        CheckForUpdateUseCase(FakeLatestReleaseSource(rollingRelease)).check("0.3.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedNonVersionReleaseTags() = runBlocking<Unit> {
        CheckForUpdateUseCase(FakeLatestReleaseSource(release("nightly"))).check("0.3.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidInstalledVersion() = runBlocking<Unit> {
        CheckForUpdateUseCase(FakeLatestReleaseSource(release("v0.3.2"))).check("development")
    }

    private fun release(
        tagName: String,
        title: String = "LumaSR $tagName",
        assetName: String = "LumaSR.apk"
    ) = LatestRelease(
        tagName = tagName,
        title = title,
        notes = "Release notes",
        pageUrl = "https://github.com/HF-CYGG/LumaSR/releases/tag/$tagName",
        assetName = assetName,
        assetSizeBytes = 128L,
        digest = "sha256:abc",
        originalDownloadUrl =
            "https://github.com/HF-CYGG/LumaSR/releases/download/$tagName/$assetName",
        acceleratedDownloadUrl =
            "https://gh.jasonzeng.dev/https://github.com/HF-CYGG/LumaSR/releases/download/$tagName/$assetName"
    )

    private class FakeLatestReleaseSource(
        private val release: LatestRelease
    ) : LatestReleaseSource {
        override suspend fun latestRelease(): LatestRelease = release
    }
}
