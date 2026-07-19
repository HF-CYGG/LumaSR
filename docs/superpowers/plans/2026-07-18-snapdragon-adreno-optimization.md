# LumaSR Snapdragon / Adreno 深度适配 Implementation Plan

**Goal:** 通过结构化 Vulkan 能力、逐机 QUICK/DEEP 校准和安全 pipeline cache，提高合格 Snapdragon 设备的持续推理吞吐，同时保留现有质量、资源和 CPU 回退边界。

**Architecture:** 在通用设备能力之上增加独立 Adreno 身份键；将 Vulkan tuning 贯通校准、持久化、执行 profile 和 native Net 缓存；将 mmap 与 pipeline cache 作为可失败的加载优化；所有生产启用都由本机证据决定。

**Constraints:** 串行 TDD；不使用子 agent、worktree、新依赖、权限、模型或新后端；不合并或推送；固定 APK 签名不变。

## Task 1：文档与基线

- 写入设计和执行计划。
- 确认当前分支、工作区、ncnn 版本和现有 JVM/native 基线。
- 文档检查和 `git diff --check` 后提交。

## Task 2：Adreno 能力画像与身份

- 先写失败测试覆盖 Qualcomm vendor、无符号字段、subgroup/queue/quirk、未知值和非 Qualcomm 身份稳定。
- 扩展 native/Kotlin 能力快照，增加 `VulkanDeviceCapabilities`、`VulkanDriverQuirks` 和独立 Adreno key。
- 覆盖驱动变化失效、旧 benchmark 兼容和日志脱敏，定向验证后提交。

## Task 3：Pipeline Cache 与 mmap

- 先写失败测试覆盖缓存键、路径约束、损坏、禁用缺陷、容量回收、原子替换及失败回退。
- 为 native CachedNet 持有 pipeline cache；只持久化生产 profile，限制单项 8 MiB、总计 32 MiB。
- 对受信模型文件启用 mmap 并保留原加载回退；补齐 telemetry 和 Net 身份后提交。

## Task 4：QUICK/DEEP 与运行时 tuning

- 先写失败测试覆盖 3/8 分钟预算、候选裁剪、中位数门槛、质量门、三档搜索、热/资源中断和结果优先级。
- 新增 `CalibrationMode`、`VulkanExecutionTuning`，扩展 schema v6 和 execution profile。
- QUICK 只优化 STANDARD Vulkan；DEEP 搜索三档 Vulkan、Winograd/SGEMM、CPU 与 TTA。
- JNI 在创建 Net 前应用 tuning；Qualcomm cooperative matrix 固定关闭；定向像素和回退验证后提交。

## Task 5：提示与报告

- 先写失败测试覆盖按 Adreno key 的一次提示、跳过、驱动更新重提示、双模式入口和报告回收。
- 设置页增加 QUICK/DEEP 入口和历史报告查看；进度日志显示 mode、能力、候选和缓存状态。
- 原子保存最近五份脱敏完整报告，每份最大 1 MiB；失败不影响校准结果发布。

## Task 6：全链路验证

- 执行 `testDebugUnitTest`、`lintDebug`、Debug/Release native、Debug/Benchmark APK 构建。
- 使用固定签名脚本构建 Release 并用 `apksigner verify --verbose --print-certs` 核对证书。
- 复核所有计划条目、`git diff --check` 和工作区范围，按中文提交拆分交付。
- 真机离线时明确保留 QUICK、DEEP、冷缓存和 30 次连续处理为待验证项。
