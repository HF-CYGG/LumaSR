# 真实档位校准真机边界补充设计

## 背景

REDMI K90 Pro Max 首轮真实档位校准证明，完整候选网格不适合作为应用内默认路径：仅 SMALL 档 Vulkan 搜索已消耗约 53 秒；CPU 搜索运行到 `tileSize=256` 时，进程 Native Heap 约 1.1 GiB，整机 PSS 与交换合计超过 2 GiB。继续枚举 STANDARD、LARGE 以及更大 CPU tile，既无法满足固定 5 分钟原子发布预算，也会制造不可接受的内存压力。

## 决策

- `InferenceCalibrationRunner` 继续支持显式注入完整候选集合，供 JVM 测试和受控实验使用。
- Android 生产接线使用保守候选预算：Vulkan 每档只测 `tileSize=128`、`commandBatchSize=1`；CPU 每档只测当前安全基线，不再枚举 tile、线程、OpenMP blocktime 或后处理线程。
- SMALL、STANDARD、LARGE 仍分别创建真实输出尺寸工作负载并实际执行；不得复制 SMALL 结果或合成未测档位。
- 每档仍比较 LEGACY_SAFE/FP32 与 OPTIMIZED/FP16，只有现有质量门和速度门同时通过时才发布 Vulkan profile；CPU profile 记录真实基线控制值。
- TTA 专属质量与速度校准继续沿用现有有界流程。
- 超时、升温、取消或任一探针失败时保持原子发布，不保存部分 profile。

## 兼容与风险

- 不更改 benchmark schema、模型、精度质量门、`minSdk`、权限、签名或运行时回退。
- 默认校准不再声称搜索到全局最优参数；它只为当前设备建立经过真实档位测量的安全启用证据。
- 完整候选搜索能力保留为显式配置，后续只有在独立基准工具具备逐候选资源隔离和更长预算时才可启用。

## 验收

- 单测证明 Android 生产接线使用保守候选预算。
- 单测证明每个分辨率档仍执行真实 Vulkan 参考、受控优化候选和 CPU 基线，并发布三个实测 profile。
- 真机校准必须在 5 分钟内结束，期间不得再次触发首轮观察到的 GiB 级 Native Heap 增长。
- 校准成功后实际处理日志必须显示 profile 命中；没有真机数据的设备和 OEM 不作兼容或提速声明。
