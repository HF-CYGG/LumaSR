import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.test") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}

val bundletoolClasspath = configurations.create("bundletoolClasspath")

dependencies {
    add(bundletoolClasspath.name, "com.android.tools.build:bundletool:1.18.1")
}

val playBundleFile = layout.projectDirectory.file("app/build/outputs/bundle/play/app-play.aab")
val playUniversalApks = layout.buildDirectory.file("bundletool/play-universal.apks")
val bundletoolTestKeystore = layout.buildDirectory.file("bundletool/test-signing.jks")
val androidSdkDirectory = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: Properties().apply {
        rootProject.file("local.properties").inputStream().use(::load)
    }.getProperty("sdk.dir")
    ?: throw GradleException("Android SDK path is unavailable. Set ANDROID_SDK_ROOT or sdk.dir.")
val bundletoolAapt2 = file(androidSdkDirectory).resolve("build-tools/36.0.0/aapt2.exe")

fun JavaExec.configureBundletool(commandArguments: List<String>) {
    group = "verification"
    classpath = bundletoolClasspath
    mainClass.set("com.android.tools.build.bundletool.BundleToolMain")
    args(commandArguments)
}

tasks.register<JavaExec>("dumpPlayBundleConfig") {
    description = "Prints the Google Play App Bundle configuration."
    dependsOn(":app:bundlePlay")
    configureBundletool(
        listOf(
            "dump",
            "config",
            "--bundle=${playBundleFile.asFile.absolutePath}"
        )
    )
}

tasks.register<JavaExec>("dumpPlayBundleManifest") {
    description = "Prints the Google Play App Bundle base manifest."
    dependsOn(":app:bundlePlay")
    configureBundletool(
        listOf(
            "dump",
            "manifest",
            "--bundle=${playBundleFile.asFile.absolutePath}",
            "--module=base"
        )
    )
}

tasks.register<JavaExec>("buildPlayUniversalApks") {
    description = "Builds a local universal APK set from the Google Play App Bundle."
    dependsOn(":app:bundlePlay")
    doFirst {
        delete(playUniversalApks)
        if (!bundletoolAapt2.isFile) {
            throw GradleException("Android SDK Build Tools 36 aapt2 is missing: ${bundletoolAapt2.absolutePath}")
        }
        val testKeystore = bundletoolTestKeystore.get().asFile
        if (!testKeystore.isFile) {
            testKeystore.parentFile.mkdirs()
            providers.exec {
                commandLine(
                    "keytool",
                    "-genkeypair",
                    "-noprompt",
                    "-keystore",
                    testKeystore.absolutePath,
                    "-storetype",
                    "JKS",
                    "-storepass",
                    "android",
                    "-keypass",
                    "android",
                    "-alias",
                    "androiddebugkey",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "10000",
                    "-dname",
                    "CN=LumaSR Bundle Verification"
                )
            }.result.get().assertNormalExitValue()
        }
    }
    configureBundletool(
        listOf(
            "build-apks",
            "--bundle=${playBundleFile.asFile.absolutePath}",
            "--output=${playUniversalApks.get().asFile.absolutePath}",
            "--mode=universal",
            "--aapt2=${bundletoolAapt2.absolutePath}",
            "--ks=${bundletoolTestKeystore.get().asFile.absolutePath}",
            "--ks-pass=pass:android",
            "--ks-key-alias=androiddebugkey",
            "--key-pass=pass:android"
        )
    )
    outputs.file(playUniversalApks)
}

tasks.register<JavaExec>("getPlayDownloadSize") {
    description = "Estimates the compressed download size of the locally generated Play APK set."
    dependsOn("buildPlayUniversalApks")
    configureBundletool(
        listOf(
            "get-size",
            "total",
            "--apks=${playUniversalApks.get().asFile.absolutePath}"
        )
    )
}
