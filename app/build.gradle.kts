import java.security.KeyStore
import java.security.MessageDigest
import groovy.json.JsonSlurper

val qnnEnabled = providers.gradleProperty("lumasr.enableQnn")
    .map(String::toBooleanStrict)
    .orElse(false)
val qnnPackageDirectory = providers.environmentVariable("LUMASR_QNN_PACKAGE_DIR")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lumasr"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lumasr"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.1"
        buildConfigField("boolean", "LUMASR_QNN_ENABLED", qnnEnabled.get().toString())

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    val uploadStoreFile = providers.environmentVariable("LUMASR_UPLOAD_STORE_FILE")
    val uploadStorePassword = providers.environmentVariable("LUMASR_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = providers.environmentVariable("LUMASR_UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = providers.environmentVariable("LUMASR_UPLOAD_KEY_PASSWORD")
    val debugCrashOnProcessingFailure = providers.gradleProperty("lumasr.crashOnProcessingFailure").orElse("true")
    val hasUploadSigningConfig = listOf(
        uploadStoreFile,
        uploadStorePassword,
        uploadKeyAlias,
        uploadKeyPassword
    ).all { it.isPresent }
    val fixedReleaseCertificateSha256 =
        "43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F"

    val verifyReleaseSigningCertificate = tasks.register("verifyReleaseSigningCertificate") {
        group = "verification"
        description = "Rejects Release builds that do not use the fixed LumaSR signing certificate."

        doLast {
            if (!hasUploadSigningConfig) {
                throw GradleException(
                    "Release signing is required. Use scripts/build-fixed-release.ps1."
                )
            }

            val keystoreFile = file(uploadStoreFile.get())
            val keystoreType = when (keystoreFile.extension.lowercase()) {
                "p12", "pfx" -> "PKCS12"
                else -> "JKS"
            }
            val keystore = KeyStore.getInstance(keystoreType)
            keystoreFile.inputStream().use { input ->
                keystore.load(input, uploadStorePassword.get().toCharArray())
            }
            val certificate = keystore.getCertificate(uploadKeyAlias.get())
                ?: throw GradleException("Release signing alias was not found in the configured keystore.")
            val actualCertificateSha256 = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }

            if (actualCertificateSha256 != fixedReleaseCertificateSha256) {
                throw GradleException(
                    "Release signing certificate mismatch. Expected " +
                        "$fixedReleaseCertificateSha256 but found $actualCertificateSha256. " +
                        "Use scripts/build-fixed-release.ps1."
                )
            }
        }
    }

    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        dependsOn(verifyReleaseSigningCertificate)
    }

    signingConfigs {
        if (hasUploadSigningConfig) {
            create("upload") {
                storeFile = file(uploadStoreFile.get())
                storePassword = uploadStorePassword.get()
                keyAlias = uploadKeyAlias.get()
                keyPassword = uploadKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "LUMASR_CRASH_ON_PROCESSING_FAILURE", debugCrashOnProcessingFailure.get())
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            buildConfigField("boolean", "LUMASR_CRASH_ON_PROCESSING_FAILURE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadSigningConfig) {
                signingConfig = signingConfigs.getByName("upload")
            }
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            buildConfigField("boolean", "LUMASR_CRASH_ON_PROCESSING_FAILURE", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("test").java.srcDir(
            rootProject.file("benchmark-shared/src/main/kotlin")
        )
        if (qnnEnabled.get() && qnnPackageDirectory.isPresent) {
            getByName("main").jniLibs.srcDir(
                file(qnnPackageDirectory.get()).resolve("jniLibs")
            )
            getByName("main").assets.srcDir(
                file(qnnPackageDirectory.get()).resolve("assets")
            )
        }
    }
}

val verifyQnnPackage = tasks.register("verifyQnnPackage") {
    group = "verification"
    description = "Verifies the external QAIRT package lock and every packaged artifact hash."
    inputs.property("qnnEnabled", qnnEnabled)

    doLast {
        if (!qnnEnabled.get()) return@doLast
        if (!qnnPackageDirectory.isPresent) {
            throw GradleException(
                "QNN is enabled but LUMASR_QNN_PACKAGE_DIR is missing. " +
                    "Generate and lock the QAIRT package outside the repository first."
            )
        }
        val packageRoot = file(qnnPackageDirectory.get()).canonicalFile
        val lockFile = packageRoot.resolve("qnn-package-lock.json")
        if (!lockFile.isFile) {
            throw GradleException("Missing QNN package lock: ${lockFile.path}")
        }
        @Suppress("UNCHECKED_CAST")
        val lock = JsonSlurper().parse(lockFile) as? Map<String, Any?>
            ?: throw GradleException("QNN package lock must be a JSON object.")
        if ((lock["schemaVersion"] as? Number)?.toInt() != 1) {
            throw GradleException("Unsupported QNN package lock schema.")
        }
        val qairtVersion = lock["qairtVersion"] as? String
            ?: throw GradleException("QNN package lock is missing qairtVersion.")
        if (!Regex("2\\.46\\.[0-9]+").matches(qairtVersion)) {
            throw GradleException("QAIRT package must use the approved 2.46.x toolchain.")
        }
        @Suppress("UNCHECKED_CAST")
        val artifacts = lock["artifacts"] as? List<Map<String, Any?>>
            ?: throw GradleException("QNN package lock is missing artifacts.")
        if (artifacts.isEmpty()) {
            throw GradleException("QNN package lock cannot have an empty artifact list.")
        }
        val seenPaths = mutableSetOf<String>()
        artifacts.forEach { artifact ->
            val relativePath = artifact["path"] as? String
                ?: throw GradleException("QNN artifact path must be a string.")
            val expectedSha256 = artifact["sha256"] as? String
                ?: throw GradleException("QNN artifact sha256 must be a string.")
            if (!Regex("[0-9a-f]{64}").matches(expectedSha256)) {
                throw GradleException("QNN artifact hash must be lowercase SHA-256: $relativePath")
            }
            val normalizedPath = relativePath.replace('\\', '/')
            val allowed = normalizedPath.startsWith("jniLibs/arm64-v8a/") ||
                normalizedPath.startsWith("assets/qnn/")
            if (!allowed || normalizedPath.startsWith("/") || ".." in normalizedPath.split('/')) {
                throw GradleException("QNN artifact path escapes approved package roots: $relativePath")
            }
            if (!seenPaths.add(normalizedPath)) {
                throw GradleException("Duplicate QNN artifact path: $relativePath")
            }
            val artifactFile = packageRoot.resolve(normalizedPath).canonicalFile
            if (!artifactFile.toPath().startsWith(packageRoot.toPath()) || !artifactFile.isFile) {
                throw GradleException("Missing or escaped QNN artifact: $relativePath")
            }
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(artifactFile.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (actualSha256 != expectedSha256) {
                throw GradleException("QNN artifact hash mismatch: $relativePath")
            }
        }
        val requiredFiles = setOf(
            "jniLibs/arm64-v8a/liblumasr_qnn_adapter.so",
            "jniLibs/arm64-v8a/libQnnSystem.so",
            "jniLibs/arm64-v8a/libQnnHtp.so",
            "assets/qnn/graph-manifest.json"
        )
        val missing = requiredFiles - seenPaths
        if (missing.isNotEmpty()) {
            throw GradleException("QNN package is missing required artifacts: ${missing.sorted()}")
        }
        if (seenPaths.any { it.contains("calibration", ignoreCase = true) }) {
            throw GradleException("Calibration corpus files must never be packaged in the APK.")
        }
        val packagedFiles = listOf("jniLibs", "assets")
            .flatMap { directoryName ->
                val directory = packageRoot.resolve(directoryName)
                if (!directory.isDirectory) emptyList() else directory.walkTopDown()
                    .filter(File::isFile)
                    .map { file ->
                        packageRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    }
                    .toList()
            }
            .toSet()
        if (packagedFiles != seenPaths) {
            val unlocked = (packagedFiles - seenPaths).sorted()
            val missingFiles = (seenPaths - packagedFiles).sorted()
            throw GradleException(
                "QNN package contents do not exactly match the lock. " +
                    "unlocked=$unlocked missing=$missingFiles"
            )
        }
        val graphManifestFile = packageRoot.resolve("assets/qnn/graph-manifest.json")
        @Suppress("UNCHECKED_CAST")
        val graphManifest = JsonSlurper().parse(graphManifestFile) as? Map<String, Any?>
            ?: throw GradleException("QNN graph manifest must be a JSON object.")
        if ((graphManifest["schemaVersion"] as? Number)?.toInt() != 1) {
            throw GradleException("Unsupported QNN graph manifest schema.")
        }
        @Suppress("UNCHECKED_CAST")
        val graphs = graphManifest["graphs"] as? List<Map<String, Any?>>
            ?: throw GradleException("QNN graph manifest is missing graphs.")
        if (graphs.isEmpty()) throw GradleException("QNN graph manifest cannot be empty.")
        graphs.forEach { graph ->
            val graphKey = graph["graphKey"] as? String
                ?: throw GradleException("QNN graph key must be a string.")
            if (!Regex("[a-z0-9][a-z0-9._-]{0,127}").matches(graphKey)) {
                throw GradleException("Invalid QNN graph key: $graphKey")
            }
            val artifactPath = (graph["artifactPath"] as? String)?.replace('\\', '/')
                ?: throw GradleException("QNN graph artifactPath must be a string.")
            if (!artifactPath.startsWith("assets/qnn/") || artifactPath !in seenPaths) {
                throw GradleException("QNN graph artifact is not locked: $artifactPath")
            }
            val precision = graph["precision"] as? String
            if (precision !in setOf("FP16", "A16W8")) {
                throw GradleException("Unsupported QNN graph precision: $precision")
            }
            val tileSize = (graph["tileSize"] as? Number)?.toInt()
            if (tileSize !in setOf(128, 256)) {
                throw GradleException("Unsupported QNN graph tile size: $tileSize")
            }
            if ((graph["cpuPartitions"] as? Number)?.toInt() != 0) {
                throw GradleException("QNN graph contains a CPU partition: $graphKey")
            }
            @Suppress("UNCHECKED_CAST")
            val unsupportedOperators = graph["unsupportedOperators"] as? List<Any?>
                ?: throw GradleException("QNN graph must declare unsupportedOperators.")
            if (unsupportedOperators.isNotEmpty()) {
                throw GradleException("QNN graph has unsupported operators: $graphKey")
            }
        }
    }
}

tasks.matching { task -> task.name.startsWith("pre") && task.name.endsWith("Build") }
    .configureEach { dependsOn(verifyQnnPackage) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
