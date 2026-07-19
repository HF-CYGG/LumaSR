# GitHub Release 更新检查 Implementation Plan

> **执行方式：** 当前会话内联执行；不使用子智能体或 Git worktree。

**Goal:** 在不使用自有服务器和 Gitee APK 分发的前提下，为 LumaSR 增加 GitHub Release 自动/手动更新检查与国内加速下载入口。

**Architecture:** 领域层负责版本与检查结果，数据层负责 GitHub API、JSON 和本地节流偏好，独立 `UpdateViewModel` 管理更新 UI 状态，Compose 根层展示弹窗并用系统浏览器打开下载链接。客户端只使用 GitHub API 返回的 `browser_download_url`，校验后为国内下载添加 `https://gh.jasonzeng.dev/` 前缀。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、StateFlow、Coroutines、HttpURLConnection、kotlinx-serialization-json、JUnit 4。

## Global Constraints

- 不新增依赖，不使用自有服务器，不调用 Gitee Release API。
- 默认自动检查开启，前台启动每 24 小时最多自动尝试一次；手动检查不节流。
- 当前 `release` 标签兼容为无更新；新版本标签使用 `vX.Y.Z`，解析器兼容最多四段数字。
- 只打开系统浏览器，不在 App 内下载或安装 APK。
- 只接受 GitHub `HF-CYGG/LumaSR` Release 路径中的 HTTPS APK。
- 不改变图片本地处理、模型、推理、存储、固定 APK 签名或 `minSdk`。
- 当前实施不提交、推送或创建 PR。

---

### Task 1: 版本、Release 解析与检查用例

**Files:**
- Create: `app/src/main/java/com/lumasr/domain/AppVersion.kt`
- Create: `app/src/main/java/com/lumasr/domain/AppUpdate.kt`
- Create: `app/src/main/java/com/lumasr/data/GitHubReleaseSource.kt`
- Create: `app/src/test/java/com/lumasr/domain/AppVersionTest.kt`
- Create: `app/src/test/java/com/lumasr/domain/CheckForUpdateUseCaseTest.kt`
- Create: `app/src/test/java/com/lumasr/data/GitHubReleaseParserTest.kt`

**Interfaces:**
- Produces: `AppVersion.parse(value: String): AppVersion?`
- Produces: `LatestReleaseSource.latestRelease(): LatestRelease`
- Produces: `CheckForUpdateUseCase.check(installedVersion: String): UpdateCheckResult`
- Produces: `GitHubReleaseParser.parse(payload: String): LatestRelease`
- Produces: `buildAcceleratedDownloadUrl(originalUrl: String): String`

- [ ] **Step 1: 写版本与检查用例失败测试**

  覆盖 `0.3.10 > 0.3.9`、`v1.0.5.1 > v1.0.5`、非法标签拒绝、远端较新、相等/较旧以及遗留 `release` 标签。

- [ ] **Step 2: 运行领域目标测试验证 RED**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.domain.AppVersionTest" --tests "com.lumasr.domain.CheckForUpdateUseCaseTest" --console=plain`

  Expected: 因 `AppVersion` 和 `CheckForUpdateUseCase` 尚不存在而编译失败。

- [ ] **Step 3: 实现最小领域类型和用例**

  定义不可变版本段、`LatestRelease`、`AvailableUpdate`、`UpdateCheckResult.Available/UpToDate`、`LatestReleaseSource` 和用例；仅为测试所需行为编码。

- [ ] **Step 4: 运行领域目标测试验证 GREEN**

  Run: 同 Step 2。

  Expected: 两个领域测试类全部通过。

- [ ] **Step 5: 写 Release JSON 与 URL 失败测试**

  使用真实结构 JSON 验证 `LumaSR.apk` 优先、唯一旧 `.apk` 兼容、多个歧义 APK 拒绝、错误 host/path 拒绝、可选 digest 和加速前缀。

- [ ] **Step 6: 运行数据目标测试验证 RED**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.data.GitHubReleaseParserTest" --console=plain`

  Expected: 因解析器和链接函数不存在而编译失败。

- [ ] **Step 7: 实现解析器与 HttpURLConnection Source**

  `GitHubReleaseSource` 请求 `/releases/latest`，发送 GitHub Accept/API-Version/User-Agent 头，连接超时 4 秒、读取超时 8 秒，非 200 状态映射为安全异常，始终断开连接。

- [ ] **Step 8: 运行数据目标测试验证 GREEN 并执行范围验证**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.domain.*" --tests "com.lumasr.data.GitHubReleaseParserTest" --console=plain`

  Expected: 新增领域与解析测试全部通过。

---

### Task 2: 自动检查节流、偏好与独立 ViewModel

**Files:**
- Create: `app/src/main/java/com/lumasr/data/UpdatePreferencesRepository.kt`
- Create: `app/src/main/java/com/lumasr/ui/UpdateUiState.kt`
- Create: `app/src/main/java/com/lumasr/ui/UpdateViewModel.kt`
- Create: `app/src/test/java/com/lumasr/ui/UpdateCheckPolicyTest.kt`

**Interfaces:**
- Consumes: `CheckForUpdateUseCase.check(installedVersion: String)`
- Produces: `shouldAutomaticallyCheck(enabled: Boolean, lastAttemptAtMillis: Long?, nowMillis: Long): Boolean`
- Produces: `UpdateViewModel.onHostStarted()`, `checkNow()`, `setAutomaticEnabled(Boolean)`, `dismissUpdateDialog()`, `reportLinkOpenFailure()`
- Produces: `UpdateUiState` with `automaticEnabled`, `lastAttemptAtMillis`, `status`, `showUpdateDialog`

- [ ] **Step 1: 写 24 小时节流失败测试**

  覆盖关闭自动检查、从未检查、未满 24 小时、恰好 24 小时、时钟回拨和未来时间。

- [ ] **Step 2: 运行节流测试验证 RED**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.ui.UpdateCheckPolicyTest" --console=plain`

  Expected: 因策略函数不存在而编译失败。

- [ ] **Step 3: 实现偏好、状态、策略和 ViewModel**

  `SharedPreferences` 只保存开关与最近自动尝试时间；自动错误静默、手动错误可见；并发检查复用单个 Job，结果更新不可变 StateFlow。

- [ ] **Step 4: 运行节流测试验证 GREEN 并执行范围验证**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.ui.UpdateCheckPolicyTest" --tests "com.lumasr.domain.CheckForUpdateUseCaseTest" --console=plain`

  Expected: 策略和用例测试全部通过。

---

### Task 3: Manifest、生命周期、设置页与更新弹窗

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/lumasr/MainActivity.kt`
- Modify: `app/src/main/java/com/lumasr/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/lumasr/ui/SettingsScreenV2.kt`
- Modify: `app/src/test/java/com/lumasr/ui/SettingsScreenV2Test.kt`

**Interfaces:**
- Consumes: `UpdateViewModel.uiState` 和事件方法。
- Produces: 设置页自动检查开关、手动检查入口、状态副标题、根层更新弹窗、国内/原始浏览器跳转。

- [ ] **Step 1: 写设置页结构失败测试**

  验证 `SettingsScreenV2` 接收 `UpdateUiState`、开关和手动检查回调，包含“软件更新”“自动检查更新”“检查更新”及仅请求版本元数据的隐私文案。

- [ ] **Step 2: 运行设置页测试验证 RED**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.ui.SettingsScreenV2Test" --console=plain`

  Expected: 新接口与文案尚不存在，测试失败。

- [ ] **Step 3: 增加 INTERNET 权限与 Compose 接线**

  `MainActivity` 创建独立 `UpdateViewModel` 并在 `onStart` 触发自动检查；`MainScreen` 收集状态、用系统 `ACTION_VIEW` 打开 URL、处理启动失败；设置页展示开关和状态；可更新时展示纯文本弹窗。

- [ ] **Step 4: 运行设置页测试验证 GREEN**

  Run: 同 Step 2。

  Expected: `SettingsScreenV2Test` 全部通过。

- [ ] **Step 5: 执行编译与范围质量验证**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`

  Expected: JVM 测试、Android Lint 和 Debug APK 构建全部成功。

---

### Task 4: 完整复核

**Files:**
- Review: all files changed by Tasks 1-3

- [ ] **Step 1: 复查需求和失败路径**

  逐项检查 GitHub 单源、动态 URL、`release` 兼容、v 标签、国内加速、原始备用、每天一次、手动检查、无 Token、系统浏览器和隐私文案。

- [ ] **Step 2: 运行最终验证**

  Run: `./gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks --console=plain`

  Expected: `BUILD SUCCESSFUL`，测试 XML 无 failure/error。

- [ ] **Step 3: 检查差异和工作区**

  Run: `git diff --check`、`git diff --stat`、`git status --short --branch`。

  Expected: 只包含更新检查设计、计划、实现与测试；无构建产物、密钥、Token、APK 或无关改动。
