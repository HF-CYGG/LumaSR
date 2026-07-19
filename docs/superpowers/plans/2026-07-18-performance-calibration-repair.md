# LumaSR 真机性能校准复盘修复 Implementation Plan

**Goal:** 在五分钟和有界内存内完成真实设备自动校准，保存安全负证据，并只用真实 benchmark 结果启用 Vulkan FP16 与 pipeline depth 2。

**Architecture:** 将有效校准结论与运行故障分离；以 schema v5 保存三档 Vulkan 终态；使用预热、缓存遥测、候选预检和资源看门狗驱动自适应搜索；把校准所得最大 pipeline depth 通过执行 profile 传入运行时策略。

**Constraints:** 串行 TDD；不使用子 agent、工作树、新依赖、权限或 `largeHeap`；不修改模型、质量门、`minSdk` 或固定签名；不合并或推送 `main`。

## Task 1：校准负证据与 schema v5

- 先写失败测试，覆盖三档正/负结论、质量拒绝仍发布安全记录、性能拒绝不终止 CPU 校准、v3/v4 兼容读取和新身份失效。
- 新增 `CalibratedVulkanTier` 与类型化 decision；升级文件 schema 和结果验证。
- 将 UI 状态拆分为优化完成与安全完成，错误原因映射为中文，不再直接显示枚举名。
- 定向测试、完整相关测试和 `git diff --check` 后提交。

## Task 2：五分钟有界自适应搜索

- 先写失败测试，覆盖 tile 搜索、batch 后反超、CPU 坐标下降、保守平局、复测门槛与总时间预算。
- Android 生产接线启用有界候选；按先 tile、再 batch、再最终性能门的顺序执行。
- 扩展探针测量遥测，正式样本要求缓存命中并允许一次重新预热。
- 定向测试和 Lint 后提交。

## Task 3：pipeline depth 2 审批

- 先写失败测试，证明默认 depth 1、旧记录 depth 1、只有 profile 请求且资源与速度门通过时 depth 2。
- 扩展 profile、参数和任务序列化；探针允许固定 depth 测量。
- 移除生产恒 false 审批，改为“profile 上限 + 运行时资源/温控降级”。
- 补齐 depth 1/2 像素、slot、取消、回退和有序输出测试后提交。

## Task 4：资源看门狗、进度与诊断

- 先写失败测试，覆盖 slot 预检、PSS/Native Heap 增量、低内存、Moderate 温控、步骤跳过和 ETA 单调性。
- 每秒采样资源并在越界时取消当前探针；任何异常都不发布部分结果。
- 增加 `STEP_SKIPPED`，结算跳过工作量并记录真实原因；保留完整终端日志。
- 定向测试和 UI 回归后提交。

## Task 5：全链路验证

- 回归 SAF、任务回收、热暂停缓存、Extreme、内存 pipeline、TTA 和固定签名。
- 执行 JVM、Lint、Debug/Benchmark、原生 Debug/Release 与固定签名 Release 构建。
- 验证 APK 证书 SHA-256 为 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`。
- 真机下线期间明确保留 Redmi cacheHit、PSS、depth 2 和实际提速为待验证项。
