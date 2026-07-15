# SR Performance Branch Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Do not dispatch subagents because the repository instructions explicitly require inline execution for this task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep `minSdk 26` while repairing Android 8–9 result saving, bounding persistent task storage, and applying thermal controls at native tile boundaries.

**Architecture:** Compose launches SAF from ViewModel-owned state, a file-backed maintenance boundary reconciles durable and in-memory tasks, and a nullable runtime-control provider carries thermal decisions through Kotlin/JNI into the existing native tile loops. Each unit is independently testable and committed.

**Tech Stack:** Kotlin 1.9, Jetpack Compose, Coroutines/StateFlow, Android Activity Result APIs, JNI/C++17, ncnn, JUnit 4, Gradle Android Plugin.

## Global Constraints

- Keep `minSdk = 26`, `targetSdk = 35`, and existing MediaStore behavior on API 29+.
- Add no dependencies and request no broad storage permission.
- Preserve local-only processing, atomic native publication, existing task schemas, and retryable CRITICAL thermal pauses.
- Do not change tile geometry, backend, precision, threads, or model-cache identity during an active native invocation.
- Do not push, build a release artifact, or claim physical-device/OEM validation without evidence.

---

### Task 1: Android 8–9 SAF result saving

**Files:**
- Create: `app/src/main/java/com/lumasr/ui/DocumentSaveQueue.kt`
- Test: `app/src/test/java/com/lumasr/ui/DocumentSaveQueueTest.kt`
- Modify: `app/src/main/java/com/lumasr/data/GalleryRepository.kt`
- Modify: `app/src/main/java/com/lumasr/ui/LumaViewModel.kt`
- Modify: `app/src/main/java/com/lumasr/ui/MainScreen.kt`

**Interfaces:**

```kotlin
data class DocumentSavePrompt(
    val requestId: Long,
    val displayName: String,
    val mimeType: String,
    val launched: Boolean
)

fun GalleryRepository.copyImageToUri(sourceFile: File, destinationUri: Uri): Uri
fun LumaViewModel.markDocumentSaveLaunched(requestId: Long)
fun LumaViewModel.completeDocumentSave(requestId: Long, destinationUri: Uri)
fun LumaViewModel.cancelDocumentSave(requestId: Long)
```

- [ ] Write failing pure JVM tests proving queue start, launch marking, success advance, cancellation, failure stop, and stale request rejection.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests com.lumasr.ui.DocumentSaveQueueTest --console=plain`; expect compilation failure because the queue types do not exist.
- [ ] Implement the queue as an immutable state machine; keep source `RenderResultInfo` entries private to ViewModel and expose only `DocumentSavePrompt`.
- [ ] Route `saveResultToGallery()` by SDK: API 29+ MediaStore, API 26–28 queue. Register a PNG `CreateDocument` launcher in `MainScreen`, launch only `launched == false`, and return result/cancel with the matching request ID.
- [ ] Make destination copying close both streams, validate non-empty source, and best-effort delete a partial SAF target on failure.
- [ ] Run the targeted test, `testDebugUnitTest`, and `git diff --check`; expect all to pass.
- [ ] Commit `Support SAF result saving on Android 8 and 9`.

### Task 2: Persistent task retention

**Files:**
- Create: `app/src/main/java/com/lumasr/domain/ProcessingTaskMaintenance.kt`
- Create: `app/src/main/java/com/lumasr/data/FileProcessingTaskMaintenance.kt`
- Test: `app/src/test/java/com/lumasr/data/FileProcessingTaskMaintenanceTest.kt`
- Modify/Test: `app/src/main/java/com/lumasr/domain/ProcessingCoordinator.kt` and its test.
- Modify: Android controller/runtime factories to inject the file maintenance implementation.

**Interfaces:**

```kotlin
data class ProcessingTaskMaintenanceResult(val removedTaskIds: Set<String>)

fun interface ProcessingTaskMaintenance {
    suspend fun maintain(nowEpochMs: Long): ProcessingTaskMaintenanceResult
}
```

- [ ] Write failing tests for exact 7-day eligibility, newest-100 retention, active-task protection, canonical-root protection, 24-hour orphan cleanup, 7-day corrupt-record cleanup, partial deletion, idempotence, and coordinator snapshot removal.
- [ ] Run the maintenance and coordinator test classes; expect missing-type/behavior failures.
- [ ] Implement candidate selection sorted by terminal time, creation time, and ID. Re-read each candidate before deletion and only return IDs whose task record was removed.
- [ ] Validate recursive targets with `canonicalFile.parentFile == canonicalRoot`; never derive deletion targets from untrusted record paths.
- [ ] Invoke maintenance before each durable refresh; catch per-candidate cleanup failures so queue recovery continues.
- [ ] Run targeted tests, full JVM tests, and `git diff --check`; expect all to pass.
- [ ] Commit `Bound persisted processing task retention`.

### Task 3: Native tile-boundary thermal controls

**Files:**
- Modify/Test: adaptive policy, runner, processor contract, Hybrid/Native/Mock processors.
- Modify: `app/src/main/cpp/superres_engine.h`, JNI bridge, engine tile loops, and native checks.

**Interfaces:**

```kotlin
data class RuntimeInferenceControl(
    val commandBatchSize: Int,
    val thermalHeadroomPercent: Int,
    val shouldPause: Boolean
)

fun interface RuntimeInferenceControlProvider {
    fun current(): RuntimeInferenceControl
}

suspend fun SuperResProcessor.process(
    params: UpscaleParams,
    onProgress: (UpscaleProgress) -> Unit,
    runtimeControlProvider: RuntimeInferenceControlProvider? = null
): UpscaleResult
```

- [ ] Write failing Kotlin tests for NORMAL/LIGHT baseline, MODERATE half batch plus 10%, SEVERE batch 1 plus 20%, NORMAL recovery, initial/runtime CRITICAL, and sampling exceptions.
- [ ] Add failing C++ checks for command batch clamping to `1..8`, headroom clamping to `0..100`, next-batch application, CPU cooldown, and pause-before-next-tile.
- [ ] Run targeted Kotlin tests and a Debug native build; expect failures caused by missing runtime-control support.
- [ ] Implement a JNI control sink that catches provider exceptions and retains its last valid value. Query it only at native tile/command-batch boundaries.
- [ ] Apply `max(params.gpuHeadroomPercent, thermalHeadroomPercent)` on Vulkan and thermal headroom on CPU; preserve static behavior when the provider is null.
- [ ] Ensure CRITICAL cancellation is consumed before stitching/publication and maps only thermally initiated cancellation to `RetryLater(THERMAL_CRITICAL)`.
- [ ] Run targeted tests, full JVM tests, Debug native build for `arm64-v8a` and `x86_64`, and `git diff --check`; expect all to pass.
- [ ] Commit `Apply thermal controls at native tile boundaries`.

### Task 4: Final verification

- [ ] Run `./gradlew.bat testDebugUnitTest --console=plain` and confirm zero failed tests.
- [ ] Run `./gradlew.bat lintDebug --console=plain` and confirm zero lint errors.
- [ ] Run `./gradlew.bat :app:assembleDebug :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain` and confirm all variants build.
- [ ] Run `git diff --check`, `git status --short --branch`, and inspect commits/diffs for scope.
- [ ] Check `adb devices`. Only if a target exists, run applicable connected tests; otherwise record the device-validation gap explicitly.
