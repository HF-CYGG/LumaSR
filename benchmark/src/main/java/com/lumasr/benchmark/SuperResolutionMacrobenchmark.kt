package com.lumasr.benchmark

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.ln
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val BENCHMARK_ALBUM = "LumaSRBenchmark"

@LargeTest
@RunWith(AndroidJUnit4::class)
class SuperResolutionMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val generatedAssets = mutableListOf<android.net.Uri>()

    @After
    fun cleanGeneratedData() {
        val failures = mutableListOf<String>()
        generatedAssets.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onSuccess { deletedRows ->
                    if (deletedRows != 1) {
                        failures += "media delete returned $deletedRows for $uri"
                    }
                }
                .onFailure { error ->
                    failures += "media delete failed for $uri: ${error.message}"
                }
        }
        generatedAssets.clear()
        runCatching { device.executeShellCommand("pm clear $TARGET_PACKAGE") }
            .onSuccess { output ->
                val cleared = output.trim() == "Success"
                if (!cleared) {
                    failures += "pm clear returned '${output.trim()}'"
                }
            }
            .onFailure { error ->
                failures += "pm clear failed: ${error.message}"
            }
        check(failures.isEmpty()) {
            "Cleanup failure(s): ${failures.joinToString("; ")}"
        }
    }

    @Test
    fun realCugan12Mp2xCold() = benchmark(REALCUGAN_12_MP, CacheMode.COLD)

    @Test
    fun realCugan12Mp2xWarm() = benchmark(REALCUGAN_12_MP, CacheMode.WARM)

    @Test
    fun waifu2x4Mp2xCold() = benchmark(WAIFU2X_4_MP, CacheMode.COLD)

    @Test
    fun waifu2x4Mp2xWarm() = benchmark(WAIFU2X_4_MP, CacheMode.WARM)

    @Test
    fun animeVideo4Mp2xCold() = benchmark(ANIME_VIDEO_4_MP, CacheMode.COLD)

    @Test
    fun animeVideo4Mp2xWarm() = benchmark(ANIME_VIDEO_4_MP, CacheMode.WARM)

    private fun benchmark(scenario: Scenario, cacheMode: CacheMode) {
        ensurePhysicalGateIsInformationalOnly()
        val profileMode = requestedProfileMode()
        generatedAssets += SyntheticMediaAsset.create(
            context = context,
            scenarioId = scenario.id,
            width = scenario.width,
            height = scenario.height
        )
        grantNotificationPermissionIfNeeded()
        val samples = mutableListOf<ScenarioSample>()
        var calibrationPrepared = false

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = cacheMode.totalIterations,
            startupMode = null,
            setupBlock = {
                prepareScenario(
                    scenario = scenario,
                    cacheMode = cacheMode,
                    prepareCalibration = profileMode == ProfileMode.OPTIMIZED && !calibrationPrepared,
                    isFirstSample = samples.isEmpty()
                )
                if (profileMode == ProfileMode.OPTIMIZED) {
                    calibrationPrepared = true
                }
            },
            measureBlock = {
                samples += measureScenario(scenario, profileMode)
            }
        )

        check(samples.size == cacheMode.totalIterations) {
            "Expected ${cacheMode.totalIterations} app samples, got ${samples.size}"
        }
        if (cacheMode.discardedSamples > 0) {
            emitDiscardedWarmupResult(scenario, profileMode, samples.first())
        }
        emitMeasuredResults(
            scenario = scenario,
            cacheMode = cacheMode,
            profileMode = profileMode,
            measured = samples.drop(cacheMode.discardedSamples).take(MEASURED_ITERATIONS)
        )
    }

    private fun MacrobenchmarkScope.prepareScenario(
        scenario: Scenario,
        cacheMode: CacheMode,
        prepareCalibration: Boolean,
        isFirstSample: Boolean
    ) {
        if (cacheMode == CacheMode.COLD || isFirstSample) {
            killProcess()
        }
        launchAndSelectScenario(scenario)
        if (prepareCalibration) {
            calibrateSelectedModelThroughSettings(scenario)
            // Calibration itself performs native inference. Restart so the first benchmark sample is cold.
            killProcess()
            launchAndSelectScenario(scenario)
        }
        selectStableProcessingDefaults()
        scrollToStartButton().also { start ->
            check(start.isEnabled) { "Start button stayed disabled for ${scenario.id}" }
        }
        device.executeShellCommand("logcat -c")
    }

    private fun MacrobenchmarkScope.launchAndSelectScenario(scenario: Scenario) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        dismissOptionalFirstRunPrompt()
        returnToEditorIfNeeded()
        openPhotoPicker()
        selectNewestGeneratedImage(scenario.id)
        selectModel(scenario.modelCardText)
    }

    private fun MacrobenchmarkScope.measureScenario(
        scenario: Scenario,
        profileMode: ProfileMode
    ): ScenarioSample {
        val startButton = scrollToStartButton()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        startButton.click()
        val completed = device.wait(
            Until.findObject(By.text(COMPLETION_TEXT)),
            scenario.timeoutMillis
        )
        assertNotNull("${scenario.id} did not reach the result screen", completed)
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
        device.waitForIdle()
        val nativeFields = waitForNativePerformanceEvidence(scenario)
        assertExpectedNativeProfile(scenario, profileMode, nativeFields)
        return ScenarioSample(
            elapsedMs = elapsedNanos / 1_000_000.0,
            nativeFields = nativeFields
        )
    }

    private fun calibrateSelectedModelThroughSettings(scenario: Scenario) {
        navigateTo("设置")
        val calibrationRow = device.wait(
            Until.findObject(By.text("本机性能校准")),
            UI_TIMEOUT_MS
        ) ?: error("Cannot open calibration settings for ${scenario.id}")
        calibrationRow.click()
        val ready = device.wait(
            Until.findObject(By.text(CALIBRATION_READY_TEXT)),
            CALIBRATION_TIMEOUT_MS
        )
        assertNotNull("Calibration did not become READY for ${scenario.modelId}", ready)
        navigateTo("处理")
    }

    private fun selectStableProcessingDefaults() {
        val gpu = scrollUntilFound(By.text("GPU"), maxSwipes = 12)
            ?: error("Cannot select the stable AUTO GPU profile")
        gpu.click()
        device.waitForIdle()
        val automaticTile = scrollUntilFound(By.text("自动"), maxSwipes = 6)
            ?: error("Cannot select automatic tile sizing")
        automaticTile.click()
        device.waitForIdle()
    }

    private fun navigateTo(destination: String) {
        val navigationItem = waitForAny(
            selectors = listOf(By.desc(destination), By.text(destination)),
            timeoutMillis = UI_TIMEOUT_MS
        ) ?: error("Cannot navigate to '$destination'")
        navigationItem.click()
        device.waitForIdle()
    }

    private fun assertExpectedNativeProfile(
        scenario: Scenario,
        profileMode: ProfileMode,
        nativeFields: Map<String, String>
    ) {
        val expectedBackend = if (profileMode == ProfileMode.OPTIMIZED) "1" else "0"
        val expectedPrecision = if (profileMode == ProfileMode.OPTIMIZED) "1" else "0"
        check(nativeFields["backend"] == expectedBackend) {
            "${scenario.id} expected ${profileMode.backendLabel}, " +
                "but native backend=${nativeFields["backend"] ?: "missing"} " +
                "fallback=${nativeFields["fallback"] ?: "missing"}"
        }
        check(nativeFields["precision"] == expectedPrecision) {
            "${scenario.id} expected ${profileMode.precisionLabel}, " +
                "but native precision=${nativeFields["precision"] ?: "missing"} " +
                "fallback=${nativeFields["fallback"] ?: "missing"}"
        }
        check(nativeFields["accel"] == "vulkan") {
            "${scenario.id} expected the requested AUTO GPU path, accel=${nativeFields["accel"]}"
        }
        check(nativeFields["region"] == "-1") {
            "${scenario.id} expected a whole-task native record, region=${nativeFields["region"]}"
        }
        check(nativeFields["retry"] == "0") {
            "${scenario.id} must not publish fallback/retry evidence as the measured run"
        }
        check(nativeFields["modelKey"] == scenario.expectedNativeModelKey) {
            "${scenario.id} expected native modelKey=${scenario.expectedNativeModelKey}, " +
                "got ${nativeFields["modelKey"] ?: "missing"}"
        }
        listOf("driverId", "driverVersion").forEach { field ->
            check(nativeFields[field] !in INVALID_DRIVER_IDENTITY_VALUES) {
                "${scenario.id} requires a non-zero Vulkan $field, got ${nativeFields[field]}"
            }
        }
    }

    private fun dismissOptionalFirstRunPrompt() {
        val dismissTexts = listOf(
            "暂不校准",
            "稍后",
            "跳过",
            "以后再说",
            "取消",
            "Not now",
            "Skip"
        )
        val immediatelyVisible = dismissTexts.firstNotNullOfOrNull { text ->
            device.findObject(By.text(text))
        }
        val dismiss = immediatelyVisible ?: device.wait(
            Until.findObject(By.text("暂不校准")),
            FIRST_RUN_PROMPT_TIMEOUT_MS
        )
        dismiss?.click()
        device.waitForIdle()
    }

    private fun returnToEditorIfNeeded() {
        device.findObject(By.text("返回"))?.click()
        device.waitForIdle()
    }

    private fun openPhotoPicker() {
        val pickerButton = waitForAny(
            selectors = listOf(
                By.desc("批量选择图片"),
                By.text("选择图片")
            ),
            timeoutMillis = UI_TIMEOUT_MS
        ) ?: error("Cannot find the production Photo Picker entry")
        pickerButton.click()
        check(device.wait(Until.gone(By.pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS)) {
            "System Photo Picker did not open"
        }
    }

    private fun selectNewestGeneratedImage(scenarioId: String) {
        openBenchmarkAlbum()
        val displayName = SyntheticMediaAsset.displayName(scenarioId)
        val exactAsset = waitForAny(
            selectors = listOf(
                By.text(displayName),
                By.descContains(displayName)
            ),
            timeoutMillis = 1_500L
        )
        val thumbnail = exactAsset ?: singleBenchmarkAlbumThumbnail()
        thumbnail.click()

        if (!device.hasObject(By.pkg(TARGET_PACKAGE))) {
            val confirm = waitForAny(
                selectors = listOf(
                    By.res(Pattern.compile(".*:id/(button_add|add_button|button_done|done_button)")),
                    By.text(Pattern.compile("(?i)(add|done|select|choose|添加|完成|选择|确定)(\\s*\\(?\\d+\\)?)?"))
                ),
                timeoutMillis = UI_TIMEOUT_MS
            )
            confirm?.click()
        }
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), PICKER_TIMEOUT_MS)) {
            "Photo Picker did not return to LumaSR"
        }
        device.waitForIdle()
    }

    private fun openBenchmarkAlbum() {
        val album = waitForAny(
            selectors = listOf(
                By.text(BENCHMARK_ALBUM),
                By.textContains(BENCHMARK_ALBUM),
                By.descContains(BENCHMARK_ALBUM)
            ),
            timeoutMillis = 1_500L
        ) ?: run {
            val albumsTab = waitForAny(
                selectors = listOf(
                    By.text(Pattern.compile("(?i)(albums|相册)")),
                    By.desc(Pattern.compile("(?i)(albums|相册)"))
                ),
                timeoutMillis = UI_TIMEOUT_MS
            ) ?: error("Cannot locate the Albums tab in the system Photo Picker")
            albumsTab.click()
            waitForAny(
                selectors = listOf(
                    By.text(BENCHMARK_ALBUM),
                    By.textContains(BENCHMARK_ALBUM),
                    By.descContains(BENCHMARK_ALBUM)
                ),
                timeoutMillis = PICKER_TIMEOUT_MS
            ) ?: error("Cannot locate the private-safe $BENCHMARK_ALBUM album")
        }
        album.click()
        verifyBenchmarkAlbumTitle()
    }

    private fun verifyBenchmarkAlbumTitle() {
        device.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val title = listOf(
                By.text(BENCHMARK_ALBUM),
                By.desc(BENCHMARK_ALBUM)
            ).asSequence()
                .flatMap { selector -> device.findObjects(selector).asSequence() }
                .firstOrNull { candidate -> !candidate.isClickable }
            if (title != null) return
            SystemClock.sleep(100)
        }
        error("Photo Picker did not expose a verified $BENCHMARK_ALBUM page title")
    }

    private fun singleBenchmarkAlbumThumbnail(): UiObject2 {
        val thumbnailSelector = By.res(
            Pattern.compile(".*:id/(icon_thumbnail|thumbnail|photo_thumbnail|image_thumbnail)")
        )
        check(device.wait(Until.hasObject(thumbnailSelector), PICKER_TIMEOUT_MS)) {
            "Cannot locate the generated image inside verified album $BENCHMARK_ALBUM"
        }
        val thumbnails = device.findObjects(thumbnailSelector)
        check(thumbnails.size == 1) {
            "Expected one generated image in $BENCHMARK_ALBUM, found ${thumbnails.size}; " +
                "refusing an ambiguous picker selection"
        }
        return thumbnails.single()
    }

    private fun selectModel(cardText: String) {
        val model = scrollUntilFound(By.text(cardText), maxSwipes = 8)
            ?: error("Cannot find model card '$cardText'")
        model.click()
        device.waitForIdle()
    }

    private fun scrollToStartButton(): UiObject2 {
        return scrollUntilFound(By.text(START_TEXT), maxSwipes = 12)
            ?: error("Cannot find '$START_TEXT'")
    }

    private fun scrollUntilFound(selector: BySelector, maxSwipes: Int): UiObject2? {
        repeat(maxSwipes + 1) { attempt ->
            device.findObject(selector)?.let { return it }
            if (attempt < maxSwipes) {
                val x = device.displayWidth / 2
                device.swipe(
                    x,
                    device.displayHeight * 72 / 100,
                    x,
                    device.displayHeight * 35 / 100,
                    24
                )
                device.waitForIdle()
            }
        }
        return null
    }

    private fun waitForAny(selectors: List<BySelector>, timeoutMillis: Long): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            selectors.firstNotNullOfOrNull(device::findObject)?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun waitForNativePerformanceEvidence(scenario: Scenario): Map<String, String> {
        val deadline = SystemClock.uptimeMillis() + NATIVE_LOG_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val lines = device.executeShellCommand("logcat -d -v raw -s LumaSRNative:I")
                .lineSequence()
                .filter { it.startsWith("Performance task=") }
                .toList()
            if (lines.isNotEmpty()) {
                // Completion UI is already visible; a short settle avoids accepting a partial log snapshot.
                SystemClock.sleep(NATIVE_LOG_SETTLE_MS)
                return validateNativePerformanceEvidence(
                    scenario = scenario,
                    lines = device.executeShellCommand("logcat -d -v raw -s LumaSRNative:I")
                        .lineSequence()
                        .filter { it.startsWith("Performance task=") }
                        .toList()
                )
            }
            SystemClock.sleep(250)
        }
        error("${scenario.id} did not emit native stage timings")
    }

    private fun validateNativePerformanceEvidence(
        scenario: Scenario,
        lines: List<String>
    ): Map<String, String> {
        val evidence = lines.map(::parseNativeFields)
        val taskIds = evidence.mapNotNull { fields -> fields["task"] }
            .filter(String::isNotBlank)
            .distinct()
        check(taskIds.size == 1 && evidence.all { fields -> fields["task"] == taskIds.single() }) {
            "${scenario.id} expected one native task after log reset, got $taskIds"
        }
        val finalEvidence = evidence.filter { fields ->
            fields["region"] == "-1" && fields["retry"] == "0"
        }
        check(finalEvidence.size == 1) {
            "${scenario.id} expected exactly one final region=-1 retry=0 record, " +
                "got ${finalEvidence.size} from ${lines.size} lines"
        }
        val nativeFields = finalEvidence.single()
        REQUIRED_NATIVE_EVIDENCE_FIELDS.forEach { field ->
            val value = nativeFields[field]
            check(!value.isNullOrBlank() && value != "missing" && value != "unknown") {
                "${scenario.id} native evidence is missing required field '$field'"
            }
        }
        return nativeFields
    }

    private fun parseNativeFields(line: String): Map<String, String> {
        return NATIVE_FIELD_PATTERN.findAll(line)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    private fun emitDiscardedWarmupResult(
        scenario: Scenario,
        profileMode: ProfileMode,
        sample: ScenarioSample
    ) {
        val native = formatNativeFields(sample.nativeFields)
        val runtimeIdentity = runtimeIdentityFields(scenario, profileMode, sample.nativeFields)
        Log.i(
            OUTPUT_TAG,
            "LUMASR_BENCHMARK schema=1 phase=warmup_discarded scenario=${scenario.id} " +
                "modelId=${scenario.modelId} requestedProfile=${profileMode.outputLabel} " +
                "cache=warm width=${scenario.width} height=${scenario.height} tta=false iteration=0 " +
                "elapsedMs=${format(sample.elapsedMs)} ${deviceIdentityFields()} $runtimeIdentity $native"
        )
    }

    private fun emitMeasuredResults(
        scenario: Scenario,
        cacheMode: CacheMode,
        profileMode: ProfileMode,
        measured: List<ScenarioSample>
    ) {
        check(measured.size == MEASURED_ITERATIONS) {
            "Expected $MEASURED_ITERATIONS measured samples, got ${measured.size}"
        }
        val identity = deviceIdentityFields()
        val runtimeIdentities = measured.map { sample ->
            runtimeIdentityFields(scenario, profileMode, sample.nativeFields)
        }.distinct()
        check(runtimeIdentities.size == 1) {
            "${scenario.id} runtime identity changed across measured samples: $runtimeIdentities"
        }
        val runtimeIdentity = runtimeIdentities.single()
        measured.forEachIndexed { index, sample ->
            val native = formatNativeFields(sample.nativeFields)
            Log.i(
                OUTPUT_TAG,
                "LUMASR_BENCHMARK schema=1 phase=measured scenario=${scenario.id} " +
                    "modelId=${scenario.modelId} requestedProfile=${profileMode.outputLabel} " +
                    "cache=${cacheMode.name.lowercase()} " +
                    "width=${scenario.width} height=${scenario.height} tta=false iteration=${index + 1} " +
                    "elapsedMs=${format(sample.elapsedMs)} $identity $runtimeIdentity $native"
            )
        }
        val elapsed = measured.map(ScenarioSample::elapsedMs)
        val sorted = elapsed.sorted()
        val median = sorted[sorted.size / 2]
        val geometricMean = exp(elapsed.map(::ln).average())
        val thirdVsFirst = elapsed.last() / elapsed.first()
        Log.i(
            OUTPUT_TAG,
            "LUMASR_BENCHMARK schema=1 phase=summary scenario=${scenario.id} " +
                "modelId=${scenario.modelId} requestedProfile=${profileMode.outputLabel} " +
                "cache=${cacheMode.name.lowercase()} " +
                "samples=${elapsed.size} medianMs=${format(median)} geometricMeanMs=${format(geometricMean)} " +
                "thirdVsFirst=${format(thirdVsFirst)} $identity $runtimeIdentity"
        )
    }

    private fun formatNativeFields(nativeFields: Map<String, String>): String {
        return NATIVE_OUTPUT_FIELDS.joinToString(" ") { key ->
            "$key=${nativeFields[key] ?: "missing"}"
        }
    }

    private fun runtimeIdentityFields(
        scenario: Scenario,
        profileMode: ProfileMode,
        nativeFields: Map<String, String>
    ): String {
        val backend = nativeFields.getValue("backend")
        val precision = nativeFields.getValue("precision")
        val modelKey = nativeFields.getValue("modelKey")
        val driverId = nativeFields.getValue("driverId")
        val driverVersion = nativeFields.getValue("driverVersion")
        val calibrationIdentity = if (profileMode == ProfileMode.OPTIMIZED) {
            "${scenario.modelId}:$modelKey:$driverId:$driverVersion"
        } else {
            "none"
        }
        return "profileIdentity=${profileMode.outputLabel}:$backend:$precision " +
            "calibrationIdentity=$calibrationIdentity gpuDriverId=$driverId " +
            "gpuDriverVersion=$driverVersion"
    }

    private fun deviceIdentityFields(): String {
        val fingerprintHash = MessageDigest.getInstance("SHA-256")
            .digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "manufacturer=${token(Build.MANUFACTURER)} deviceModel=${token(Build.MODEL)} " +
            "device=${token(Build.DEVICE)} sdk=${Build.VERSION.SDK_INT} abi=${token(Build.SUPPORTED_ABIS.firstOrNull())} " +
            "buildId=${token(Build.ID)} fingerprintSha256=$fingerprintHash"
    }

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                device.executeShellCommand(
                    "pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS"
                )
            }
        }
    }

    private fun requestedProfileMode(): ProfileMode {
        val value = InstrumentationRegistry.getArguments()
            .getString(PROFILE_ARGUMENT)
            ?.trim()
            ?.lowercase(Locale.US)
        return when (value) {
            null,
            "",
            "optimized" -> ProfileMode.OPTIMIZED
            "legacy" -> ProfileMode.LEGACY
            else -> error("Unsupported $PROFILE_ARGUMENT='$value'; use optimized or legacy")
        }
    }

    private fun ensurePhysicalGateIsInformationalOnly() {
        if (Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)
        ) {
            Log.w(
                OUTPUT_TAG,
                "Emulator detected: results are diagnostic only; physical-device acceptance gates are not asserted."
            )
        }
    }

    private fun token(value: String?): String = value
        .orEmpty()
        .trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "unknown" }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private enum class CacheMode(val discardedSamples: Int) {
        COLD(discardedSamples = 0),
        WARM(discardedSamples = 1);

        val totalIterations: Int
            get() = MEASURED_ITERATIONS + discardedSamples
    }

    private enum class ProfileMode(
        val outputLabel: String,
        val backendLabel: String,
        val precisionLabel: String
    ) {
        OPTIMIZED("CALIBRATED_AUTO_GPU", "OPTIMIZED", "FP16"),
        LEGACY("LEGACY_AUTO_GPU", "LEGACY_SAFE", "FP32")
    }

    private data class ScenarioSample(
        val elapsedMs: Double,
        val nativeFields: Map<String, String>
    )

    private data class Scenario(
        val id: String,
        val modelId: String,
        val modelCardText: String,
        val expectedNativeModelKey: String,
        val width: Int,
        val height: Int,
        val timeoutMillis: Long
    )

    private companion object {
        const val TARGET_PACKAGE = "com.lumasr"
        const val OUTPUT_TAG = "LumaSRBenchmark"
        const val COMPLETION_TEXT = "本地处理完成"
        const val START_TEXT = "开始处理"
        const val CALIBRATION_READY_TEXT = "已校准，高性能模式可用"
        const val PROFILE_ARGUMENT = "lumasrBenchmarkProfile"
        const val MEASURED_ITERATIONS = 3
        const val UI_TIMEOUT_MS = 20_000L
        const val PICKER_TIMEOUT_MS = 30_000L
        const val NATIVE_LOG_TIMEOUT_MS = 5_000L
        const val NATIVE_LOG_SETTLE_MS = 250L
        const val CALIBRATION_TIMEOUT_MS = 6L * 60L * 1_000L
        const val FIRST_RUN_PROMPT_TIMEOUT_MS = 3_000L
        val NATIVE_FIELD_PATTERN = Regex("([A-Za-z][A-Za-z0-9]*)=([^\\s]+)")
        val NATIVE_OUTPUT_FIELDS = listOf(
            "task",
            "modelKey",
            "model",
            "driverId",
            "driverVersion",
            "accel",
            "backend",
            "precision",
            "fallback",
            "requestedPipelineDepth",
            "pipelineDepth",
            "peakOccupiedSlots",
            "peakSlotBytes",
            "producerWaitMs",
            "consumerWaitMs",
            "overlapMs",
            "threads",
            "blocktimeMs",
            "postprocessThreads",
            "batch",
            "cacheCapacity",
            "cacheHit",
            "cacheSize",
            "retry",
            "region",
            "tile",
            "decodeMs",
            "modelLoadMs",
            "tileInputMs",
            "tileExtractMs",
            "tileCopyMs",
            "saveMs",
            "preprocessMs",
            "uploadMs",
            "inferenceMs",
            "downloadMs",
            "postprocessMs",
            "encodeMs",
            "totalMs"
        )
        val REQUIRED_NATIVE_EVIDENCE_FIELDS = listOf(
            "task",
            "modelKey",
            "driverId",
            "driverVersion",
            "region",
            "retry",
            "backend",
            "precision",
            "requestedPipelineDepth",
            "pipelineDepth",
            "peakOccupiedSlots",
            "peakSlotBytes",
            "producerWaitMs",
            "consumerWaitMs",
            "overlapMs",
            "postprocessThreads",
            "totalMs"
        )
        val INVALID_DRIVER_IDENTITY_VALUES = setOf(
            null,
            "",
            "0",
            "0x00000000",
            "missing",
            "unknown"
        )
        val REALCUGAN_12_MP = Scenario(
            id = "realcugan_12mp_2x",
            modelId = "realcugan-standard",
            modelCardText = "标准",
            expectedNativeModelKey = "realcugan-standard",
            width = 4_000,
            height = 3_000,
            timeoutMillis = 30 * 60 * 1_000L
        )
        val WAIFU2X_4_MP = Scenario(
            id = "waifu2x_4mp_2x",
            modelId = "waifu2x-cunet",
            modelCardText = "CUnet",
            expectedNativeModelKey = "waifu2x-cunet",
            width = 2_000,
            height = 2_000,
            timeoutMillis = 20 * 60 * 1_000L
        )
        val ANIME_VIDEO_4_MP = Scenario(
            id = "animevideo_4mp_2x",
            modelId = "realesrgan-animevideo-x2",
            modelCardText = "视频",
            expectedNativeModelKey = "realesr-animevideov3-x2",
            width = 2_000,
            height = 2_000,
            timeoutMillis = 20 * 60 * 1_000L
        )
    }
}

private object SyntheticMediaAsset {
    fun displayName(scenarioId: String): String = "lumasr_benchmark_$scenarioId.png"

    fun create(
        context: Context,
        scenarioId: String,
        width: Int,
        height: Int
    ): android.net.Uri {
        val resolver = context.contentResolver
        val displayName = displayName(scenarioId)
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("lumasr_benchmark_%")
        )
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$BENCHMARK_ALBUM"
            )
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create synthetic benchmark asset")
        val bitmap = renderPattern(width, height)
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Cannot encode synthetic benchmark asset"
                }
            } ?: error("Cannot open synthetic benchmark asset")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPattern(width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = false }
        canvas.drawColor(Color.rgb(18, 24, 38))
        val cell = (minOf(width, height) / 36).coerceAtLeast(32)
        var row = 0
        var y = 0
        while (y < height) {
            var column = 0
            var x = 0
            while (x < width) {
                val red = (37 + column * 29 + row * 11) and 0xff
                val green = (53 + column * 13 + row * 31) and 0xff
                val blue = (71 + column * 17 + row * 19) and 0xff
                paint.color = Color.rgb(red, green, blue)
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    minOf(x + cell, width).toFloat(),
                    minOf(y + cell, height).toFloat(),
                    paint
                )
                x += cell
                column += 1
            }
            y += cell
            row += 1
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (cell / 9f).coerceAtLeast(2f)
        paint.color = Color.WHITE
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
        return bitmap
    }
}
