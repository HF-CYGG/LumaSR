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
    fun treatsLegacyReleaseTagAsCurrentDuringMigration() = runBlocking {
        val result = CheckForUpdateUseCase(FakeLatestReleaseSource(release("release"))).check("0.3.1")

        assertEquals("0.3.1", (result as UpdateCheckResult.UpToDate).installedVersion.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedNonVersionReleaseTags() = runBlocking<Unit> {
        CheckForUpdateUseCase(FakeLatestReleaseSource(release("nightly"))).check("0.3.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidInstalledVersion() = runBlocking<Unit> {
        CheckForUpdateUseCase(FakeLatestReleaseSource(release("v0.3.2"))).check("development")
    }

    private fun release(tagName: String) = LatestRelease(
        tagName = tagName,
        title = "LumaSR $tagName",
        notes = "Release notes",
        pageUrl = "https://github.com/HF-CYGG/LumaSR/releases/tag/$tagName",
        assetName = "LumaSR.apk",
        assetSizeBytes = 128L,
        digest = "sha256:abc",
        originalDownloadUrl = "https://github.com/HF-CYGG/LumaSR/releases/download/$tagName/LumaSR.apk",
        acceleratedDownloadUrl = "https://gh.jasonzeng.dev/https://github.com/HF-CYGG/LumaSR/releases/download/$tagName/LumaSR.apk"
    )

    private class FakeLatestReleaseSource(
        private val release: LatestRelease
    ) : LatestReleaseSource {
        override suspend fun latestRelease(): LatestRelease = release
    }
}
