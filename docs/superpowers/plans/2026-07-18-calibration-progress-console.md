# 性能校准进度控制台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为本机性能校准增加保留终态的模态进度卡片，展示真实步骤、动态 ETA 和完整命令行式日志。

**Architecture:** Runner 通过结构化 `CalibrationProgressEvent` 报告真实探针边界；纯 Kotlin reducer 将事件转换为不可变 UI 快照并估算剩余时间；ViewModel 管理会话与每秒计时；独立 Compose Dialog 只渲染状态。现有 benchmark 原子发布、失败回退和校准参数不变。

**Tech Stack:** Kotlin、Coroutines/StateFlow、Jetpack Compose Material 3、JUnit 4、现有 Gradle/Android 工具链。

## Global Constraints

- 不新增依赖，不改变模型、校准候选、五分钟超时或 benchmark schema。
- 不改变 `minSdk`、权限、固定 APK 签名和原子发布语义。
- 不记录图片、模型目录或临时文件路径；不解析 Logcat 作为产品状态。
- 当前任务不使用子 agent 或 Git worktree，在 `codex/performance-next` 串行实施。

---

### Task 1: 结构化校准事件与 ETA reducer

**Files:**
- Create: `app/src/main/java/com/lumasr/domain/CalibrationProgress.kt`
- Create: `app/src/main/java/com/lumasr/ui/CalibrationProgressUiState.kt`
- Create: `app/src/test/java/com/lumasr/ui/CalibrationProgressUiStateTest.kt`

**Interfaces:**
- Produces: `CalibrationProgressEvent`, `CalibrationProgressEventType`, `CalibrationProgressReporter`。
- Produces: `CalibrationProgressDialogState`, `CalibrationProgressUiReducer.start/apply/tick/finish/dismiss`。

- [ ] **Step 1: Write the failing reducer tests**

覆盖：首个完成步骤前 ETA 为 `null`；完成工作量后按 `elapsed / completedWork * remainingWork` 估算；ETA 钳制在五分钟剩余预算内；日志按输入顺序完整保留；终态停止计时且只能显式 dismiss。

```kotlin
val started = CalibrationProgressUiReducer.start(startedAtElapsedMs = 1_000L)
val updated = CalibrationProgressUiReducer.apply(
    started,
    CalibrationProgressEvent.stepCompleted(
        stepIndex = 1,
        totalSteps = 11,
        completedWorkUnits = 20,
        totalWorkUnits = 100,
        elapsedMs = 10_000L,
        title = "FP32 质量基准",
        logMessage = "✓ 输出 512×512 · 耗时 1.29s"
    )
)
assertEquals(40_000L, updated.estimatedRemainingMs)
assertEquals(listOf("✓ 输出 512×512 · 耗时 1.29s"), updated.logLines.map { it.text })
```

- [ ] **Step 2: Run RED**

Run: `\.\gradlew.bat :app:testDebugUnitTest --tests "com.lumasr.ui.CalibrationProgressUiStateTest" --console=plain`

Expected: compilation fails because the new progress types do not exist.

- [ ] **Step 3: Implement the minimal event and reducer types**

`CalibrationProgressEvent` carries `type`, `stepIndex`, `totalSteps`, `completedWorkUnits`, `totalWorkUnits`, `elapsedMs`, `title`, and one safe `logMessage`. `CalibrationProgressDialogState` carries running/terminal status, progress fields, timestamps, ETA, complete log list and dismissibility. Invalid or zero work values yield `null` ETA rather than fabricated values.

- [ ] **Step 4: Run GREEN and lint the changed Kotlin**

Run the Task 1 test command, then `\.\gradlew.bat lintDebug --console=plain`.

### Task 2: Runner 报告真实探针与发布事件

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/InferenceCalibrationRunner.kt`
- Modify: `app/src/test/java/com/lumasr/domain/InferenceCalibrationRunnerTest.kt`

**Interfaces:**
- Consumes: `CalibrationProgressReporter.report(CalibrationProgressEvent)`。
- Produces: `InferenceCalibrationRunner.run(request, progressReporter)`，旧调用使用默认 no-op reporter。

- [ ] **Step 1: Write failing ordered-event tests**

为 production-bounded 候选路径断言：总步骤为 11（不支持 TTA）或 15（支持 TTA）；每档依次出现 Vulkan FP32、Vulkan FP16、CPU FP32；完成事件包含实际输出尺寸、tile/batch/CPU 参数与探针耗时；质量结果、档位选择和发布消息来自真实结果；失败路径以 ERROR/FINISHED 终止且不报告成功发布。

- [ ] **Step 2: Run RED**

Run: `\.\gradlew.bat :app:testDebugUnitTest --tests "com.lumasr.domain.InferenceCalibrationRunnerTest" --console=plain`

Expected: fails because `run` has no progress reporter and no events are emitted.

- [ ] **Step 3: Add a private progress session around probe calls**

计算计划步骤与工作量；在 `probe.run`/`probe.measure` 的每个真实调用前后报告 STARTED/COMPLETED。工作量使用输出像素，CPU 使用保守倍率，TTA 使用 pass 倍率。使用实际 `durationNanos` 生成日志，所有 reporter 调用用 `runCatching` 隔离，且日志禁止包含路径。

- [ ] **Step 4: Report decisions and terminal result**

在 FP16 质量门、Vulkan/CPU profile 选择、TTA 选择、repository publish、失败、超时和成功边界报告 NOTE/FINISHED。不得改变候选选择或返回结果。

- [ ] **Step 5: Run GREEN and `git diff --check`**

Run the Task 2 test command and `git diff --check`.

### Task 3: Coordinator 与 ViewModel 会话接线

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/OnDeviceCalibrationCoordinator.kt`
- Modify: `app/src/main/java/com/lumasr/ui/CalibrationAndroidFactory.kt`
- Modify: `app/src/main/java/com/lumasr/ui/LumaViewModel.kt`
- Modify: `app/src/test/java/com/lumasr/domain/OnDeviceCalibrationCoordinatorTest.kt`
- Modify: `app/src/test/java/com/lumasr/ui/LumaViewModelArchitectureTest.kt`
- Modify: `app/src/test/java/com/lumasr/ui/LumaUiStateSelectionTest.kt`

**Interfaces:**
- `DeviceCalibrationCoordinator.run(model, resolvedModelDir, progressReporter)`。
- `LumaUiState.calibrationDialog: CalibrationProgressDialogState?`。
- `LumaViewModel.dismissCalibrationDialog()`。

- [ ] **Step 1: Write failing wiring/state tests**

断言 coordinator 原样转发 reporter；开始校准创建对话框状态；进度事件通过 reducer 追加；完成后卡片仍存在且 `canDismiss=true`；调用 dismiss 后只清除卡片，不清除 `CalibrationUiStatus.READY/FAILED`。

- [ ] **Step 2: Run RED**

Run: `\.\gradlew.bat :app:testDebugUnitTest --tests "com.lumasr.domain.OnDeviceCalibrationCoordinatorTest" --tests "com.lumasr.ui.LumaViewModelArchitectureTest" --tests "com.lumasr.ui.LumaUiStateSelectionTest" --console=plain`.

- [ ] **Step 3: Implement coordinator and ViewModel state flow**

Factory 使用 `(request, reporter) -> runner.run(request, reporter)`。ViewModel 开始时创建状态、每秒 tick，Runner 回调只做线程安全 `_uiState.update`；最终调用 reducer.finish 并保留日志；dismiss 仅允许终态。取消 ViewModel 时停止 ticker。

- [ ] **Step 4: Run GREEN and lint**

Run the Task 3 test command, then `\.\gradlew.bat lintDebug --console=plain`.

### Task 4: Compose 模态卡片与命令行日志

**Files:**
- Create: `app/src/main/java/com/lumasr/ui/CalibrationProgressDialog.kt`
- Modify: `app/src/main/java/com/lumasr/ui/MainScreen.kt`
- Create: `app/src/test/java/com/lumasr/ui/CalibrationProgressDialogTest.kt`

**Interfaces:**
- `CalibrationProgressDialog(state, onDismiss)` is stateless and receives no ViewModel.

- [ ] **Step 1: Write failing formatter/structure tests**

断言时间格式包含 `mm:ss`/小时边界；运行态文案含步骤、已用时间和 ETA；终态有“完成”按钮；源文件使用 `LinearProgressIndicator`、等宽 `FontFamily.Monospace`、独立 `verticalScroll`、`LaunchedEffect(logLines.size)` 自动滚动，并在运行态拒绝 dismiss。

- [ ] **Step 2: Run RED**

Run: `\.\gradlew.bat :app:testDebugUnitTest --tests "com.lumasr.ui.CalibrationProgressDialogTest" --console=plain`.

- [ ] **Step 3: Implement Material 3 dialog**

顶部使用标题、`步骤 N/M`、当前步骤、进度条和双列时间信息；日志使用深色圆角 Surface、`SelectionContainer`、等宽小字号和固定最大高度；运行时 `onDismissRequest = {}`，终态按钮调用 `onDismiss`。MainScreen 根层在 `state.calibrationDialog != null` 时展示。

- [ ] **Step 4: Run GREEN, lint and Compose compilation**

Run Task 4 test, `\.\gradlew.bat lintDebug :app:assembleDebug --console=plain`.

### Task 5: 全量验证、固定签名构建与真机验收

**Files:**
- Modify only if verification exposes a scoped defect.

- [ ] **Step 1: Full JVM and Lint verification**

Run: `\.\gradlew.bat testDebugUnitTest lintDebug --console=plain`.

- [ ] **Step 2: Build signed Release and verify certificate**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-fixed-release.ps1`.

Expected certificate SHA-256: `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`.

- [ ] **Step 3: Install and run one bounded real calibration**

安装 `artifacts/releases/LumaSR-0.2.1-release-arm64-v8a.apk` 到当前 REDMI K90 Pro Max；验证卡片出现、步骤按真实事件推进、ETA 更新、日志自动滚动、运行中不可关闭、终态保留、完成按钮关闭。

- [ ] **Step 4: Capture evidence and scope claims**

记录总耗时、峰值 PSS、日志中实际 profile 命中和校准结果；只报告当前设备证据，不外推 VIVO/OPPO/其他小米设备。
