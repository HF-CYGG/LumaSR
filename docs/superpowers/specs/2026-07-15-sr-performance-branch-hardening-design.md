# SR 性能分支问题修复设计

## 背景

`codex/sr-performance` 当前保留 `minSdk 26`，但结果保存只接受 Android 10 及以上；持久任务、work spec 和任务目录没有实际回收入口；热策略虽然能计算 MODERATE/SEVERE 配置，却只在任务开始时应用，运行中只有 CRITICAL 会取消原生任务。

本修复集保持离线处理、原子输出和既有任务恢复语义，分别修复保存兼容、持久存储上限与安全边界热降载。三个单元独立实现、测试和提交，不引入依赖或广泛存储权限。

## 保存兼容

- API 29 及以上继续使用 MediaStore，并保留创建失败、复制失败时删除未完成条目的回滚。
- API 26–28 通过 `ActivityResultContracts.CreateDocument` 逐张请求用户选择目标。Compose 只负责启动系统界面；ViewModel 持有源文件队列和当前请求。
- UI 只暴露 `DocumentSavePrompt(requestId, displayName, mimeType, launched)`，不暴露源文件路径。`requestId` 用于拒绝过期回调，`launched` 防止重组重复启动。
- 成功写入后推进下一张；取消或失败会停止剩余队列，已成功结果保留并显示部分完成数量。失败目标尽力删除。
- 当前推理产物仍为 PNG；本次不增加 JPEG/WebP 转码。

## 持久任务维护

- 新增 `ProcessingTaskMaintenance` 边界，在 `ProcessingCoordinator.refreshQueue()` 读取任务前执行。维护结果只返回已经完整删除任务记录的 ID，协调器据此同步内存任务与快照。
- `QUEUED`、`RUNNING`、`PAUSED` 永不清理。`COMPLETED`、`FAILED`、`CANCELLED` 在终态时间达到 7 天，或不属于最新 100 条终态记录时回收。
- 删除顺序为任务目录、work spec、任务记录。每个候选独立处理；失败保留记录并等待下次维护，且不得阻断正常任务恢复。
- 所有递归删除目标必须规范化，并验证为 `lumasr-tasks` 的直接子项。超过 24 小时的孤立临时目录或临时文件可以删除；隔离损坏记录保留 7 天后删除。
- 维护操作幂等，不改变现有 JSON/work schema，也不执行数据迁移。

## 动态热控制

- `ThermalPolicyState` 增加 `thermalHeadroomPercent`。NORMAL/LIGHT 为 0；MODERATE 为 10 且命令批量减半；SEVERE/CRITICAL 为 20 且命令批量为 1。
- 新增 `RuntimeInferenceControl(commandBatchSize, thermalHeadroomPercent, shouldPause)` 和提供器。`SuperResProcessor.process()` 接受可空提供器；空值保持旧的静态执行行为。
- Runner 在开始前采样一次并生成初始状态。每个原生 tile 或 Vulkan 命令批次完成后重新采样；采样失败沿用最后有效状态，NORMAL 恢复基线。
- JNI 在下一安全边界读取控制值。GPU 使用静态 `gpuHeadroomPercent` 与热 headroom 的较大值；CPU 使用热 headroom。运行中不改变 tile 网格、后端、精度、线程或模型缓存。
- CRITICAL 在下一块开始前取消，不发布临时输出；Runner 将这类取消映射为 `RetryLater(THERMAL_CRITICAL)`。

## 错误与可观测性

- SAF 取消不是处理失败；提示成功数量和未保存数量。
- 维护失败按候选隔离，只记录汇总数量，不记录图片路径。
- JNI 回调必须捕获 Kotlin 侧异常并返回最后有效控制值，不能让异常跨越 native 边界。
- 热状态变化只记录级别和生效控制值；不记录输入、输出路径或图片信息。

## 验收

- JVM 测试覆盖保存队列推进、取消、失败和过期回调；维护 TTL、数量上限、路径保护、幂等与快照同步；热状态变化、恢复、CRITICAL 和采样异常。
- C++ 检查覆盖控制值钳制、Vulkan 批量边界、CPU 冷却和 CRITICAL 停止。
- 全量执行 JVM 单测、Android Lint、Debug APK、App Benchmark 与 benchmark 测试 APK 构建。
- 没有 API 26–28 模拟器或物理设备时，只报告构建与自动化验证，不声称 SAF、温控或任何 OEM 真机验证完成。
