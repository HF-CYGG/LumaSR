package com.lumasr.release

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWorkflowContractTest {
    @Test
    fun declaresV033AsAnInstallableUpgrade() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("versionCode = 4"))
        assertTrue(buildScript.contains("versionName = \"0.3.3\""))
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
        assertTrue(buildScript.contains("-ExpectedAssetName 'LumaSR.apk'"))
        assertTrue(signingVerifier.contains("verify --verbose --print-certs"))
        assertTrue(releaseVerifierFile.isFile)

        val releaseVerifier = releaseVerifierFile.readText()
        assertTrue(releaseVerifier.contains("aapt2"))
        assertTrue(releaseVerifier.contains("zipalign"))
        assertTrue(releaseVerifier.contains("ExpectedAssetName"))
    }

    @Test
    fun rollingReleaseBuildStagesOneVersionedApk() {
        val rollingScriptFile = File("../scripts/prepare-rolling-release.ps1")

        assertTrue(rollingScriptFile.isFile)
        val rollingScript = rollingScriptFile.readText()
        assertTrue(rollingScript.contains("build-fixed-release.ps1"))
        assertTrue(rollingScript.contains("LumaSR-v\$ReleaseVersion.apk"))
        assertTrue(rollingScript.contains("tagName = 'release'"))
        assertTrue(rollingScript.contains("releaseTitle = \"LumaSR v\$ReleaseVersion\""))
        assertTrue(rollingScript.contains("-ExpectedAssetName \$assetName"))
    }

    @Test
    fun rollingReleaseTagIsValidatedByGitHubActions() {
        val workflow = File("../.github/workflows/release-contract.yml").readText()
        val rollingValidator = File("../scripts/validate-rolling-release-metadata.ps1")

        assertTrue(rollingValidator.isFile)
        assertTrue(workflow.contains("- 'release'"))
        assertTrue(workflow.contains("validate-rolling-release-metadata.ps1"))
        val validatorScript = rollingValidator.readText()
        assertTrue(validatorScript.contains("TagName -cne 'release'"))
        assertTrue(validatorScript.contains("validate-release-metadata.ps1"))
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
        assertTrue(releaseGuide.contains("LumaSR-v0.3.3.apk"))
        assertTrue(releaseGuide.contains("v0.3.3"))
        assertTrue(releaseGuide.contains("不可变"))
    }
}
