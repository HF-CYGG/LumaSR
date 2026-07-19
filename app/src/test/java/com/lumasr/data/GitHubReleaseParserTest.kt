package com.lumasr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseParserTest {
    @Test
    fun parsesReleaseAndPrefersCanonicalLumaSrApk() {
        val release = GitHubReleaseParser.parse(
            releaseJson(
                tag = "v0.3.2",
                assets = """
                    ${assetJson("debug.apk", 96, githubUrl("v0.3.2", "debug.apk"))},
                    ${assetJson("LumaSR.apk", 128, githubUrl("v0.3.2", "LumaSR.apk"), "sha256:abc")}
                """.trimIndent()
            )
        )

        assertEquals("v0.3.2", release.tagName)
        assertEquals("LumaSR v0.3.2", release.title)
        assertEquals("Changes", release.notes)
        assertEquals("LumaSR.apk", release.assetName)
        assertEquals(128L, release.assetSizeBytes)
        assertEquals("sha256:abc", release.digest)
        assertEquals(githubUrl("v0.3.2", "LumaSR.apk"), release.originalDownloadUrl)
        assertEquals(
            "https://gh.jasonzeng.dev/${githubUrl("v0.3.2", "LumaSR.apk")}",
            release.acceleratedDownloadUrl
        )
    }

    @Test
    fun acceptsSingleLegacyApkWhenCanonicalNameIsAbsent() {
        val release = GitHubReleaseParser.parse(
            releaseJson(
                tag = "release",
                assets = assetJson("app-release.apk", 128, githubUrl("release", "app-release.apk"))
            )
        )

        assertEquals("app-release.apk", release.assetName)
        assertNull(release.digest)
        assertEquals(
            githubUrl("release", "app-release.apk"),
            release.originalDownloadUrl
        )
        assertEquals(
            "https://gh.jasonzeng.dev/${githubUrl("release", "app-release.apk")}",
            release.acceleratedDownloadUrl
        )
    }

    @Test
    fun acceptsSingleVersionedApkFromRollingReleaseUrl() {
        val assetName = "LumaSR-v0.3.2.apk"
        val release = GitHubReleaseParser.parse(
            releaseJson(
                tag = "release",
                assets = assetJson(assetName, 128, githubUrl("release", assetName))
            )
        )

        assertEquals(assetName, release.assetName)
        assertEquals(githubUrl("release", assetName), release.originalDownloadUrl)
        assertEquals(
            "https://gh.jasonzeng.dev/${githubUrl("release", assetName)}",
            release.acceleratedDownloadUrl
        )
    }

    @Test
    fun rejectsAmbiguousNonCanonicalApks() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseParser.parse(
                releaseJson(
                    assets = """
                        ${assetJson("first.apk", 100, githubUrl("v0.3.2", "first.apk"))},
                        ${assetJson("second.apk", 100, githubUrl("v0.3.2", "second.apk"))}
                    """.trimIndent()
                )
            )
        }

        assertEquals("Latest release does not contain one unambiguous APK asset.", error.message)
    }

    @Test
    fun rejectsAssetsOutsideTheLumaSrGitHubReleasePath() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseParser.parse(
                releaseJson(
                    assets = assetJson(
                        "LumaSR.apk",
                        128,
                        "https://example.com/HF-CYGG/LumaSR/releases/download/v0.3.2/LumaSR.apk"
                    )
                )
            )
        }
    }

    @Test
    fun buildsAcceleratedUrlOnlyForValidatedGitHubApks() {
        val original = githubUrl("v1.0.5.1", "LumaSR.apk")

        assertEquals("https://gh.jasonzeng.dev/$original", buildAcceleratedDownloadUrl(original))
        assertThrows(IllegalArgumentException::class.java) {
            buildAcceleratedDownloadUrl("https://github.com/other/repo/releases/download/v1/app.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAcceleratedDownloadUrl("http://github.com/HF-CYGG/LumaSR/releases/download/v1/app.apk")
        }
    }

    private fun releaseJson(
        tag: String = "v0.3.2",
        assets: String
    ): String = """
        {
          "tag_name": "$tag",
          "name": "LumaSR $tag",
          "body": "Changes",
          "html_url": "https://github.com/HF-CYGG/LumaSR/releases/tag/$tag",
          "draft": false,
          "prerelease": false,
          "assets": [$assets]
        }
    """.trimIndent()

    private fun assetJson(
        name: String,
        size: Long,
        url: String,
        digest: String? = null
    ): String = """
        {
          "name": "$name",
          "state": "uploaded",
          "size": $size,
          "content_type": "application/vnd.android.package-archive",
          "browser_download_url": "$url"${digest?.let { ",\n          \"digest\": \"$it\"" }.orEmpty()}
        }
    """.trimIndent()

    private fun githubUrl(tag: String, fileName: String): String =
        "https://github.com/HF-CYGG/LumaSR/releases/download/$tag/$fileName"
}
