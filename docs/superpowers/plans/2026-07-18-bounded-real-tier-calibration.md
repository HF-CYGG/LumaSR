# 有界真实档位校准 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 256MiB Android Java 堆上完成真实 SMALL、STANDARD、LARGE 性能校准，并只在真实质量与速度证据通过后启用优化路径。

**Architecture:** 将校准探针拆成“返回有界 RGB 质量样本”和“只返回完整输出尺寸与耗时”两条路径。Runner 先用固定 512×512 输出验证 FP16 质量，再用 bounds-only 探针对真实分辨率档位搜索 Vulkan/CPU 参数；策略不再让 ncnn 粗略分数否决有效真机校准。

**Tech Stack:** Kotlin、Android Bitmap/BitmapFactory、Coroutines、JUnit 4、ncnn/Vulkan、Gradle Android Plugin。

## Global Constraints

- 不新增依赖、权限、`android:largeHeap` 或工作树。
- 不改变模型、SSIM/PSNR/P99 质量门、`minSdk`、固定 APK 签名和用户数据。
- 不记录用户图片路径；临时校准 PNG 必须在所有路径删除。
- 任一尺寸错误、Moderate 及以上温控、超时、OOM、取消或持久化失败均不发布部分记录。
- 仅在 `codex/performance-next` 实施，不合并或推送 `main`。

---

### Task 1: 增加 bounds-only 原生校准探针

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/InferenceCalibrationRunner.kt`
- Modify: `app/src/main/java/com/lumasr/domain/RgbImage.kt`
- Modify: `app/src/main/java/com/lumasr/processor/NativeBitmapCalibrationProbe.kt`
- Test: `app/src/test/java/com/lumasr/processor/NativeBitmapCalibrationProbeTest.kt`

**Interfaces:**
- Consumes: `InferenceCalibrationProbe.run(input, params)`、`NativeBitmapRegionProcessor.processBitmapRegion()`。
- Produces: `CalibrationProbeMeasurement(outputWidth, outputHeight, durationNanos)` 与 `InferenceCalibrationProbe.measure(input, params)`。

- [ ] **Step 1: 写 bounds-only 失败测试**

测试注入会在完整像素解码时失败的 `outputDecoder`，同时注入返回 `2048 to 2048` 的 bounds decoder：

```kotlin
val result = probe.measure(rgbInput(), params())
assertEquals(CalibrationProbeMeasurement(2_048, 2_048, 30L), result)
assertEquals(0, fullDecodeCalls)
assertTrue(probeDirectory.listFiles().orEmpty().isEmpty())
```

- [ ] **Step 2: 运行测试确认红灯**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.processor.NativeBitmapCalibrationProbeTest --console=plain
```

Expected: FAIL，原因是 `CalibrationProbeMeasurement`、`measure()` 或 `outputBoundsDecoder` 尚不存在。

- [ ] **Step 3: 实现兼容接口和仅尺寸路径**

```kotlin
data class CalibrationProbeMeasurement(
    val outputWidth: Int,
    val outputHeight: Int,
    val durationNanos: Long
)

suspend fun measure(input: RgbImage, params: UpscaleParams): CalibrationProbeMeasurement? =
    run(input, params)?.let { output ->
        CalibrationProbeMeasurement(output.image.width, output.image.height, output.durationNanos)
    }
```

`NativeBitmapCalibrationProbe` 覆盖 `measure()`，使用 `BitmapFactory.Options.inJustDecodeBounds`。`run()` 与 `measure()` 共享负责 Bitmap、临时文件、耗时和 `finally` 删除的执行函数。输入 Bitmap 改为逐行 `setPixels()` 并直接读取 `RgbImage.channelAt()`，不创建整图 `copyBytes()` 与整图 `IntArray`。

- [ ] **Step 4: 运行探针测试和差异检查**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.processor.NativeBitmapCalibrationProbeTest --console=plain
git diff --check
```

Expected: PASS；bounds-only 测试证明完整 decoder 未调用，临时目录为空。

- [ ] **Step 5: 提交探针改造**

```powershell
git add app/src/main/java/com/lumasr/domain/InferenceCalibrationRunner.kt app/src/main/java/com/lumasr/domain/RgbImage.kt app/src/main/java/com/lumasr/processor/NativeBitmapCalibrationProbe.kt app/src/test/java/com/lumasr/processor/NativeBitmapCalibrationProbeTest.kt
git commit -m "perf: 校准探针仅读取真实输出尺寸"
```

### Task 2: 分离有界质量校验与真实档位性能搜索

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/InferenceCalibrationRunner.kt`
- Modify: `app/src/main/java/com/lumasr/domain/OnDeviceCalibrationCoordinator.kt`
- Test: `app/src/test/java/com/lumasr/domain/InferenceCalibrationRunnerTest.kt`
- Test: `app/src/test/java/com/lumasr/domain/OnDeviceCalibrationCoordinatorTest.kt`

**Interfaces:**
- Consumes: `run()` 用于质量样本；`measure()` 用于真实性能档位。
- Produces: 三档 `CalibratedVulkanProfile`、三档实测 `CalibratedCpuProfile` 与有界 `fp16Quality`。

- [ ] **Step 1: 写质量与性能分离失败测试**

```kotlin
assertEquals(2, runRequests.size)
assertTrue(runRequests.all { it.outputEdge <= 512 })
assertEquals(setOf(2_048, 3_072, 5_120), measureRequests.map { it.outputEdge }.toSet())
assertTrue(result is CalibrationRunResult.Completed)
assertEquals(3, repository.saved.single().vulkanProfiles.size)
```

另加 256MiB 堆测试：`availableHeapBytesProvider = { 256L * 1024L * 1024L }` 必须允许三档进入；低于 128MiB 的 LARGE staging 预算仍返回 `RESOURCE_CONSTRAINED` 且 repository 为空。

- [ ] **Step 2: 运行 Runner 测试确认红灯**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.domain.InferenceCalibrationRunnerTest --console=plain
```

Expected: FAIL，因为现有 Runner 对每个真实档位完整解码，并要求 128/256/512MiB 可用堆。

- [ ] **Step 3: 实现一次有界 FP16 质量校验**

```kotlin
private const val QUALITY_OUTPUT_EDGE = 512

private suspend fun calibrateFp16Quality(...): QualityProbeResult {
    val inputEdge = ceilDiv(QUALITY_OUTPUT_EDGE, request.baseParams.scale)
    val safe = probe.run(pattern, safeParams) ?: return failed(PROBE_FAILED)
    val optimized = probe.run(pattern, optimizedParams) ?: return failed(PROBE_FAILED)
    val quality = RgbQualityEvaluator.evaluate(safe.image, optimized.image)
        ?: return failed(INVALID_OUTPUT)
    return if (AdaptiveInferencePolicy.isFp16Eligible(request.device, quality)) {
        QualityProbeResult.Ready(quality)
    } else {
        failed(QUALITY_REJECTED)
    }
}
```

- [ ] **Step 4: 将真实档位改为仅测量路径**

`calibrateTier()` 和 CPU 搜索调用 `probe.measure()` 并验证 `outputWidth/outputHeight`。候选复用步骤 3 的质量证据，只比较耗时。TTA 质量使用 512 输出的 `run()`，TTA 速度使用 STANDARD 真实工作量的 `measure()`。

```kotlin
internal fun requiredAvailableHeapBytes(tier: ResolutionTier): Long = when (tier) {
    ResolutionTier.SMALL -> 64L * MEBIBYTE
    ResolutionTier.STANDARD -> 96L * MEBIBYTE
    ResolutionTier.LARGE -> 128L * MEBIBYTE
}
```

每个档位只在创建真实输入前检查一次预算；外层继续将 OOM 映射为 `PROBE_FAILED` 并禁止发布。

- [ ] **Step 5: 运行 Runner 与协调器测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.domain.InferenceCalibrationRunnerTest --tests com.lumasr.domain.OnDeviceCalibrationCoordinatorTest --console=plain
git diff --check
```

Expected: PASS；256MiB 用例发布三档真实 profile，失败用例不发布。

- [ ] **Step 6: 提交 Runner 改造和入口修复**

```powershell
git add app/src/main/java/com/lumasr/domain/InferenceCalibrationRunner.kt app/src/main/java/com/lumasr/domain/OnDeviceCalibrationCoordinator.kt app/src/test/java/com/lumasr/domain/InferenceCalibrationRunnerTest.kt app/src/test/java/com/lumasr/domain/OnDeviceCalibrationCoordinatorTest.kt
git commit -m "perf: 以有界质量样本校准真实分辨率档位"
```

### Task 3: 让有效真机校准取代粗略分数否决

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/AdaptiveInferencePolicy.kt`
- Test: `app/src/test/java/com/lumasr/domain/AdaptiveInferencePolicyTest.kt`

**Interfaces:**
- Consumes: 未过期且身份完全匹配的 `InferenceBenchmarkResult`。
- Produces: 仅在真实校准有效时返回 OPTIMIZED/FP16；无校准继续返回 LEGACY_SAFE/FP32。

- [ ] **Step 1: 写 Adreno 840 粗分数回归测试**

```kotlin
val profile = AdaptiveInferencePolicy.selectExecutionProfile(
    device = device().copy(gpuRoughScore = 21),
    benchmarkResult = validBenchmark,
    currentBenchmarkIdentity = validBenchmark.identity,
    calibratedVulkanProfile = CalibratedVulkanProfile(ResolutionTier.STANDARD, 128, 1),
    ...
)
assertEquals(NativeBackend.OPTIMIZED, profile.backend)
assertEquals(InferencePrecision.FP16, profile.precision)
```

同一设备没有有效 benchmark 时仍必须为 LEGACY_SAFE/FP32；TTA 必须额外提供合格专属 profile。

- [ ] **Step 2: 运行策略测试确认红灯**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.lumasr.domain.AdaptiveInferencePolicyTest --console=plain
```

Expected: FAIL，当前 `gpuRoughScore >= 75` 否决有效 benchmark。

- [ ] **Step 3: 移除普通和 TTA 路径的粗分数否决**

从 `canUseOptimizedFp16` 和 `canUseOptimizedTta` 删除 `gpuRoughScore` 条件。保留 Vulkan/FP16、稳定 GPU 身份、benchmark 身份、有效期、质量和 profile 条件；粗分数字段继续参与 profile hash，使能力变化失效旧记录。

- [ ] **Step 4: 运行策略测试、完整单测和 Lint**

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
git diff --check
```

Expected: 全部 PASS，无新增 Lint 错误。

- [ ] **Step 5: 提交策略修复**

```powershell
git add app/src/main/java/com/lumasr/domain/AdaptiveInferencePolicy.kt app/src/test/java/com/lumasr/domain/AdaptiveInferencePolicyTest.kt
git commit -m "perf: 依据有效真机校准启用优化路径"
```

### Task 4: 固定签名构建和 REDMI 真机闭环

**Files:**
- Verify: `scripts/build-fixed-release.ps1`
- Output: `artifacts/releases/LumaSR-0.2.1-release-arm64-v8a.apk`
- Evidence: `artifacts/device-runs/redmi-k90-pro-max-20260718/`

**Interfaces:**
- Consumes: 三个实现提交和现有固定签名环境。
- Produces: 验签 Release APK、真机校准记录与后续 OPTIMIZED/FP16 处理证据。

- [ ] **Step 1: 构建并验证固定签名 Release**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-fixed-release.ps1
```

Expected: 单测、Lint、Release 构建成功，证书 SHA-256 为 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`。

- [ ] **Step 2: 覆盖安装并启动**

```powershell
adb -s 192.168.2.216:33695 install -r artifacts/releases/LumaSR-0.2.1-release-arm64-v8a.apk
adb -s 192.168.2.216:33695 shell am start -W -n com.lumasr/.MainActivity
```

Expected: `Success`，应用数据保留且无签名冲突。

- [ ] **Step 3: 手动启动校准并自动采样**

HyperOS 禁止无线 ADB 注入触摸，用户只负责点击“本机性能校准”。Codex 持续采集 app logcat、`dumpsys meminfo com.lumasr`、`dumpsys thermalservice` 和 UI hierarchy，确认三档真实输出、无完整 Java 解码峰值、无回退和最终 READY。

- [ ] **Step 4: 运行校准后真实处理**

使用同一张测试图和 CUnet 2x/Vulkan/Auto 参数处理至少三次，日志必须出现 `backend=OPTIMIZED precision=FP16` 且无 fallback/retry；记录三次耗时和 cacheHit。

- [ ] **Step 5: 最终工作区核对**

```powershell
git status --short --branch
git log --oneline -5
git diff --check
```

Expected: 只有计划内提交，证据目录保持 ignored；不推送、不合并 main。
