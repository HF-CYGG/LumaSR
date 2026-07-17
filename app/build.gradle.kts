import java.security.KeyStore
import java.security.MessageDigest

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
        versionCode = 1
        versionName = "0.2.1"

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
    }
}

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
