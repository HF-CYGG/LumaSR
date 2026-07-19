# LumaSR Snapdragon / Adreno 深度适配设计

## 目标

在不新增依赖、模型格式、存储权限或厂商专有运行库的前提下，利用当前 ncnn Vulkan 能力对 Qualcomm Adreno 设备执行结构化识别、逐机校准和安全缓存。优化以持续吞吐为主，任何无法证明更快或质量合格的设备继续使用现有兼容路径。

## 能力与身份

- Qualcomm 仅通过 Vulkan `vendorId == 0x5143` 识别，设备名称只用于脱敏诊断。
- native 能力快照增加 device/API/driver、pipeline cache UUID、subgroup、compute queue、共享内存、FP16 和 ncnn 驱动缺陷标志。
- 保留现有通用设备 profile key；Qualcomm 额外生成 `adreno-capability-v1` 身份键并加入 benchmark identity，避免使非 Qualcomm 的有效校准无关失效。
- 驱动、pipeline cache UUID 或关键 Vulkan 能力变化会使对应 Adreno 结果失效，并允许提示一次重新校准。

## ncnn Vulkan 调优

`VulkanExecutionTuning` 保存 subgroup、shader local memory、Winograd 和 SGEMM 开关。FP16 仍由现有精度字段控制，tile、batch 和 pipeline depth 仍由现有 profile 保存。所有 tuning 都必须进入 native Net 缓存键。

Qualcomm 固定关闭 cooperative matrix；不手动固定 subgroup 宽度、workgroup 大小或 CPU affinity。`useWeightsInHostMemory` 保持关闭。能力缺失、配置非法或未校准时继续使用 `LEGACY_SAFE/FP32/batch1/depth1`。

满足路径规范的模型文件可尝试 mmap 加载，失败立即回退原加载方式。持久 pipeline cache 以 runtime、模型、GPU/driver、pipeline UUID 和 tuning 的哈希隔离，使用应用内部目录、原子发布和容量回收；存在 `corruptedOnlinePipelineCache` 缺陷时完全禁用。实验候选不写入持久缓存。

## 快速与深度校准

- `QUICK` 上限 3 分钟：质量门、STANDARD 档 Vulkan、subgroup/shader local memory/FP16、tile/batch/depth；不搜索 CPU、TTA、Winograd 或 SGEMM。
- `DEEP` 上限 8 分钟：优先复用有效 QUICK 种子，再搜索三档 Vulkan、Winograd/SGEMM、CPU 和 TTA。
- 每个候选预热一次并初测，前两名各复测两次，以三次中位数排名。单步至少快 5%，最终 Adreno profile 至少快 10%才启用。
- 继续使用 SSIM >= 0.995、PSNR >= 40 dB、P99 通道误差 <= 3 的质量门。
- Moderate 及以上热状态、低内存、资源越界、取消、失败或超时都不发布部分结果；DEEP 失败保留原 QUICK，QUICK 不降级覆盖有效 DEEP。

## 交互与诊断

Qualcomm 新身份键第一次出现时只提示一次快速校准，可跳过。设置页提供快速和深度校准入口，沿用现有进度、预计剩余时间和真实日志。日志增加结构化 GPU 身份、tuning、候选耗时和 pipeline cache 状态，不记录图片路径、模型绝对路径或原始 fingerprint。

最新五次校准报告保存在 `noBackupFilesDir`，每份最大 1 MiB，供设置页重新查看。所有报告仅包含有界、脱敏的校准事件。

## 范围边界

本轮不接入 QNN/HTP、NNAPI、PerformanceHint、自定义 GPU 驱动或静态 Adreno 代际参数表。无真机数据时只声明代码、测试和构建完成，不声明 Snapdragon 家族或 OEM 兼容通过。
