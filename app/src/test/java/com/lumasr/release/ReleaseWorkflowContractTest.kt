package com.lumasr.release

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWorkflowContractTest {
    @Test
    fun declaresV032AsAnInstallableUpgrade() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("versionCode = 3"))
        assertTrue(buildScript.contains("versionName = \"0.3.2\""))
    }

    @Test
    fun fixedReleaseBuildProducesTheCanonicalUpdateAsset() {
        val buildScript = File("../scripts/build-fixed-release.ps1").readText()
        val signingVerifier = File("../scripts/verify-fixed-release-signing.ps1").readText()
        val releaseVerifierFile = File("../scripts/verify-release-contract.ps1")

        assertTrue(buildScript.contains("verify-release-contract.ps1"))
        assertTrue(buildScript.contains("'LumaSR.apk'"))
        assertTrue(buildScript.contains("'LumaSR.apk.sha256'"))
        assertTrue(buildScript.contains("'release-manifest.json'"))
        assertTrue(signingVerifier.contains("verify --verbose --print-certs"))
        assertTrue(releaseVerifierFile.isFile)

        val releaseVerifier = releaseVerifierFile.readText()
        assertTrue(releaseVerifier.contains("aapt2"))
        assertTrue(releaseVerifier.contains("zipalign"))
        assertTrue(releaseVerifier.contains("LumaSR.apk"))
    }

    @Test
    fun githubPublicationIsExplicitAndTagGated() {
        val publishScriptFile = File("../scripts/publish-github-release.ps1")
        val workflowFile = File("../.github/workflows/release-contract.yml")
        val releaseGuideFile = File("../docs/release-process.md")

        assertTrue(publishScriptFile.isFile)
        assertTrue(workflowFile.isFile)
        assertTrue(releaseGuideFile.isFile)

        val publishScript = publishScriptFile.readText()
        assertTrue(publishScript.contains("[switch]\$Publish"))
        assertTrue(publishScript.contains("git branch --show-current"))
        assertTrue(publishScript.contains("gh release create"))
        assertTrue(publishScript.contains("--verify-tag"))

        val workflow = workflowFile.readText()
        assertTrue(workflow.contains("contents: read"))
        assertTrue(workflow.contains("'v*'"))
        assertTrue(workflow.contains("validate-release-metadata.ps1"))

        val releaseGuide = releaseGuideFile.readText()
        assertTrue(releaseGuide.contains("LumaSR.apk"))
        assertTrue(releaseGuide.contains("v0.3.2"))
        assertTrue(releaseGuide.contains("不可变"))
    }
}
