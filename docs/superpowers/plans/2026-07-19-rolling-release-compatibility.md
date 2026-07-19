# 固定 `release` 标签更新兼容 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 LumaSR 在继续复用 GitHub `release` 标签时，从严格格式的 Release 标题识别版本，并生成唯一、版本化、可验证的 v0.3.2 APK 发布物。

**Architecture:** `CheckForUpdateUseCase` 保持数值标签优先，仅对 `release` 标签回退解析标题；`GitHubReleaseParser` 继续把 API 返回的 `browser_download_url` 作为唯一下载地址来源。发布侧复用固定签名构建，再把已校验 APK 复制为版本化滚动资产，并生成对应摘要和清单。

**Tech Stack:** Kotlin、JUnit 4、PowerShell、Gradle Android Plugin、GitHub Releases REST API、Android `apksigner`/`aapt2`/`zipalign`

## Global Constraints

- 不新增生产依赖、运行时依赖或自建服务器。
- GitHub 仓库固定为 `HF-CYGG/LumaSR`，滚动标签严格为 `release`。
- Release 标题严格为 `LumaSR v<version>`；v0.3.2 的标题是 `LumaSR v0.3.2`。
- 滚动 Release 只保留一个 APK，命名为 `LumaSR-v<version>.apk`。
- 下载 URL 只使用并校验 API 返回的 `assets[].browser_download_url`。
- APK 继续使用现有固定证书签名；正式包最低 Android 10、仅 `arm64-v8a`。
- 不执行提交、合并、推送、标签移动或线上 Release 编辑。

---

### Task 1: 滚动标签版本解析

**Files:**
- Modify: `app/src/main/java/com/lumasr/domain/AppUpdate.kt`
- Modify: `app/src/test/java/com/lumasr/domain/CheckForUpdateUseCaseTest.kt`

**Interfaces:**
- Consumes: `LatestRelease.tagName`、`LatestRelease.title`、`AppVersion.parse(String)`
- Produces: `CheckForUpdateUseCase.check(String): UpdateCheckResult` 对 `release` 标题的严格版本解析

- [x] **Step 1: 写失败测试**

  添加 `readsVersionFromTitleForRollingReleaseTag`、`acceptsExistingLumaSrTitleFormatForRollingReleaseTag` 和 `rejectsRollingReleaseTitleWithoutExactVersion`，分别要求标题升级、同版本和非法标题行为。

- [x] **Step 2: 运行测试并确认预期失败**

  Run:

  ```powershell
  .\gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.domain.CheckForUpdateUseCaseTest" --console=plain
  ```

  Expected: 旧实现把 `release` 直接视为当前版本，升级断言失败；非法标题未抛异常。

- [x] **Step 3: 实现最小解析回退**

  数值标签优先；仅 `release` 使用完整正则 `^(?:LumaSR\s+)?([vV]?[0-9]+(?:\.[0-9]+){1,3})$` 解析标题。

- [x] **Step 4: 运行目标测试并确认通过**

  Expected: `CheckForUpdateUseCaseTest` 和 `GitHubReleaseParserTest` 共 12 个测试通过。

### Task 2: 版本化 APK 与滚动发布契约

**Files:**
- Modify: `app/src/test/java/com/lumasr/data/GitHubReleaseParserTest.kt`
- Modify: `app/src/test/java/com/lumasr/release/ReleaseWorkflowContractTest.kt`
- Modify: `scripts/verify-release-contract.ps1`
- Create: `scripts/prepare-rolling-release.ps1`
- Create: `scripts/validate-rolling-release-metadata.ps1`
- Modify: `.github/workflows/release-contract.yml`

**Interfaces:**
- Consumes: `scripts/build-fixed-release.ps1` 输出与 `verify-release-contract.ps1` 固定签名校验
- Produces: `artifacts/releases/release/LumaSR-v0.3.2.apk`、同名 `.sha256`、`release-manifest.json`

- [x] **Step 1: 写失败测试**

  让解析器测试以 `LumaSR-v0.3.2.apk` 验证 `/releases/download/release/` URL；让工作流契约测试要求 `prepare-rolling-release.ps1` 存在并包含 `LumaSR-v$ReleaseVersion.apk`、`tagName = 'release'`、`-ExpectedAssetName`。

- [x] **Step 2: 运行目标测试并确认预期失败**

  Run:

  ```powershell
  .\gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.data.GitHubReleaseParserTest" --tests "com.lumasr.release.ReleaseWorkflowContractTest" --console=plain
  ```

  Expected: 因滚动发布脚本和显式资产名参数不存在而失败。

- [x] **Step 3: 泛化 APK 契约校验器**

  为 `verify-release-contract.ps1` 增加必填 `ExpectedAssetName` 参数，校验实际文件名与显式值完全相等；现有构建脚本传入 `LumaSR.apk`，保持不可变构建行为不变。

- [x] **Step 4: 添加滚动发布准备脚本**

  `prepare-rolling-release.ps1 -ReleaseVersion 0.3.2 -GradleUserHome F:\Development\gradle` 调用固定签名构建，复制为 `LumaSR-v0.3.2.apk`，再次执行完整 APK 契约校验，并写入与文件名一致的 SHA-256 和滚动发布清单。

- [x] **Step 5: 运行目标测试并确认通过**

  Expected: 解析器与发布工作流契约测试全部通过。

- [x] **Step 6: 让 GitHub Actions 校验滚动标签**

  先增加失败契约测试，要求工作流监听 `release` 并调用独立的滚动元数据校验器；实现脚本从 `app/build.gradle.kts` 读取数值 `versionName`，复用不可变元数据校验器检查版本与发布说明。

### Task 3: 发布新闻稿与操作流程

**Files:**
- Modify: `docs/releases/v0.3.2.md`
- Modify: `docs/release-process.md`

**Interfaces:**
- Consumes: 固定标签兼容设计、实际 v0.3.2 功能和滚动发布物路径
- Produces: 可直接粘贴到 GitHub 的完整 Release 正文与逐步发布清单

- [x] **Step 1: 重写 v0.3.2 新闻稿**

  使用既有 v0.2.1 Release 的“简介、核心功能、安装、注意事项”结构，准确说明自动更新、动态下载 URL、国内加速入口、稳定性与性能改进；不声称未经真机验证的 OEM/QNN 效果。

- [x] **Step 2: 更新发布流程**

  明确必须编辑现有 `release` Release、标题格式、删除旧 APK、上传唯一版本化 APK、Release 与标签更新是两个独立动作，以及标签移动需另行授权。

- [x] **Step 3: 检查文档契约**

  Run:

  ```powershell
  rg -n "LumaSR v0.3.2|LumaSR-v0.3.2.apk|browser_download_url|编辑现有|移动.*标签" docs/releases/v0.3.2.md docs/release-process.md
  ```

  Expected: 每项发布规则均有明确命中，且不再把唯一资产写成固定 `LumaSR.apk`。

### Task 4: 构建与完成验证

**Files:**
- Verify: `app/src/main/**`
- Verify: `app/src/test/**`
- Produce: `artifacts/releases/release/LumaSR-v0.3.2.apk`

**Interfaces:**
- Consumes: Tasks 1–3 的实现与文档
- Produces: 本地签名 APK、SHA-256、清单和可复核验证结果

- [x] **Step 1: 运行完整发布准备**

  Run:

  ```powershell
  .\scripts\prepare-rolling-release.ps1 -ReleaseVersion 0.3.2 -GradleUserHome F:\Development\gradle
  ```

  Expected: JVM 测试、Android Lint、Release 构建、证书、版本、ZIP 对齐全部通过，并生成三个滚动发布文件。

- [x] **Step 2: 运行工作流脚本测试与差异检查**

  Run:

  ```powershell
  .\scripts\test-release-workflow.ps1
  git diff --check
  git status --short --branch
  ```

  Expected: 脚本测试和差异检查退出码为 0；工作区仅包含本任务文件与被忽略的本地发布物。

- [x] **Step 3: 复核 APK 与摘要**

  读取 `release-manifest.json`，重新计算 APK SHA-256，并确认文件名、`versionName=0.3.2`、`versionCode=3`、证书指纹和清单完全一致。

- [x] **Step 4: 交付线上手工操作清单**

  报告本地文件绝对路径、SHA-256、未执行的外部操作，并要求发布前删除旧 APK，确保线上 Release 只有一个 `.apk`。
