# 最近校准报告折叠 Implementation Plan

> **执行方式：** 当前会话内联执行；不使用子智能体或 Git worktree。

**Goal:** 设置页中的“最近校准报告”默认折叠，用户可通过标题行展开或收起全部报告。

**Architecture:** 折叠状态仅属于 `SettingsScreenV2` 的展示层，使用 `rememberSaveable` 保存，不进入 `LumaUiState` 或 ViewModel。标题行始终显示报告数量和当前展开动作；报告条目展开后继续调用现有 `onOpenCalibrationReport`。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、JUnit 4。

## Global Constraints

- 不改变校准报告持久化、排序、内容或详情弹窗。
- 默认折叠；报告为空时整个分区仍不显示。
- 不引入新依赖，不新增动画框架。
- 原实现阶段不提交、推送或创建 PR；后续已按用户明确授权，在真机安装验证成功后提交、创建 PR 并合并。

---

### Task 1: 折叠状态与设置页交互

**Files:**
- Modify: `app/src/test/java/com/lumasr/ui/SettingsScreenV2Test.kt`
- Modify: `app/src/main/java/com/lumasr/ui/SettingsScreenV2.kt`

**Interfaces:**
- Produces: `CalibrationReportsInitiallyExpanded: Boolean`
- Produces: `calibrationReportsExpandedAfterToggle(current: Boolean): Boolean`
- Produces: `calibrationReportsDisclosureAction(expanded: Boolean): String`

- [x] **Step 1: 写失败测试**

  验证默认状态为收起、连续切换可展开再收起，并验证无障碍动作文本。

- [x] **Step 2: 运行目标测试验证 RED**

  Run: `.\gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.ui.SettingsScreenV2Test" --console=plain`

  Expected: 因折叠状态接口尚不存在而编译失败。

- [x] **Step 3: 实现最小 Compose 折叠 UI**

  使用 `rememberSaveable` 保存局部状态；增加可点击标题行、报告数量和 `ExpandMore`/`ExpandLess` 图标；仅在展开时组合报告条目。

- [x] **Step 4: 运行目标测试验证 GREEN**

  Run: `.\gradlew.bat -g F:\Development\gradle :app:testDebugUnitTest --tests "com.lumasr.ui.SettingsScreenV2Test" --console=plain`

  Expected: `SettingsScreenV2Test` 全部通过。

- [x] **Step 5: 完整验证**

  Run: `.\gradlew.bat -g F:\Development\gradle testDebugUnitTest lintDebug assembleDebug --console=plain`

  Expected: JVM 测试、Android Lint 和 Debug 构建全部通过。

- [x] **Step 6: 复核差异**

  检查 `git diff --check`、`git diff --stat` 和最终工作区状态，只保留计划、设置页和测试三处变更。
