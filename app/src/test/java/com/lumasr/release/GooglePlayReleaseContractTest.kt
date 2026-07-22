package com.lumasr.release

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayReleaseContractTest {
    private val appBuildScript = File("build.gradle.kts").readText()
    private val rootBuildScript = File("../build.gradle.kts").readText()

    @Test
    fun targetsAndroid16WithTheNextReleaseVersion() {
        assertTrue(rootBuildScript.contains("version \"8.13.2\""))
        assertTrue(appBuildScript.contains("compileSdk = 36"))
        assertTrue(appBuildScript.contains("targetSdk = 36"))
        assertTrue(appBuildScript.contains("versionCode = 5"))
        assertTrue(appBuildScript.contains("versionName = \"0.3.4\""))
    }

    @Test
    fun declaresAnIsolatedPlayUploadSigningInterface() {
        assertTrue(appBuildScript.contains("LUMASR_PLAY_UPLOAD_STORE_FILE"))
        assertTrue(appBuildScript.contains("LUMASR_PLAY_UPLOAD_STORE_PASSWORD"))
        assertTrue(appBuildScript.contains("LUMASR_PLAY_UPLOAD_KEY_ALIAS"))
        assertTrue(appBuildScript.contains("LUMASR_PLAY_UPLOAD_KEY_PASSWORD"))
        assertTrue(appBuildScript.contains("LUMASR_PLAY_UPLOAD_CERT_SHA256"))
        assertTrue(appBuildScript.contains("create(\"play\")"))
        assertTrue(appBuildScript.contains("initWith(getByName(\"release\"))"))
        assertTrue(!appBuildScript.contains("applicationIdSuffix"))
    }

    @Test
    fun alignsTheNativeReleaseFor16KbPages() {
        val cmakeScript = File("src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmakeScript.contains("target_link_options(localsr PRIVATE"))
        assertTrue(cmakeScript.contains("-Wl,-z,max-page-size=16384"))
        assertTrue(cmakeScript.contains("-Wl,-z,common-page-size=16384"))
    }

    @Test
    fun providesRepeatableGooglePlaySigningAndBuildScripts() {
        val initializer = File("../scripts/initialize-google-play-upload-key.ps1")
        val builder = File("../scripts/build-google-play-release.ps1")
        val releaseGuide = File("../docs/google-play-release.md")

        assertTrue(initializer.isFile)
        assertTrue(builder.isFile)
        assertTrue(releaseGuide.isFile)

        val initializerScript = initializer.readText()
        assertTrue(initializerScript.contains("RSA"))
        assertTrue(initializerScript.contains("4096"))
        assertTrue(initializerScript.contains("lumasr-play-upload-cert.pem"))
        assertTrue(initializerScript.contains("LUMASR_PLAY_UPLOAD_CERT_SHA256"))

        val buildScript = builder.readText()
        assertTrue(buildScript.contains("'assemblePlay'"))
        assertTrue(buildScript.contains("bundlePlay"))
        assertTrue(buildScript.contains("LumaSR-0.3.4-google-play.aab"))
        assertTrue(buildScript.contains("LumaSR-v0.3.4.apk"))
        assertTrue(buildScript.contains("mapping.txt"))
        assertTrue(buildScript.contains("native-debug-symbols.zip"))
        assertTrue(buildScript.contains("release-manifest.json"))
        assertTrue(buildScript.contains("ExtractToDirectory(\$universalApkPath"))
        assertTrue(buildScript.contains("getPlayDownloadSize"))
        assertTrue(buildScript.contains("estimatedCompressedDownloadBytesMin"))
        assertTrue(buildScript.contains("estimatedCompressedDownloadBytesMax"))

        val releaseGuideText = releaseGuide.readText()
        assertTrue(releaseGuideText.contains("com.lumasr"))
        assertTrue(releaseGuideText.contains("提供现有应用签名密钥"))
        assertTrue(releaseGuideText.contains("LumaSR-0.3.4-google-play.aab"))
    }

    @Test
    fun exposesPinnedBundletoolVerificationTasks() {
        assertTrue(rootBuildScript.contains("com.android.tools.build:bundletool:1.18.1"))
        assertTrue(rootBuildScript.contains("dumpPlayBundleConfig"))
        assertTrue(rootBuildScript.contains("dumpPlayBundleManifest"))
        assertTrue(rootBuildScript.contains("buildPlayUniversalApks"))
        assertTrue(rootBuildScript.contains("getPlayDownloadSize"))
        assertTrue(rootBuildScript.contains("--aapt2="))
    }
}
