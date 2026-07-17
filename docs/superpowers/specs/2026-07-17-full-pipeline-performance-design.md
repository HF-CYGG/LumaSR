# LumaSR 全链路性能优化设计

## 目标

在不改变模型、输出质量门、固定 APK 签名、存储权限或最低系统版本的前提下，减少图像输入复制、极限导出临时 I/O、多阶段 PNG 中转和 Vulkan/CPU 串行等待，并扩展设备校准与 TTA 优化。所有新路径必须保留当前稳定实现作为自动回退；没有真机证据时不得声明实际提速或 OEM 兼容。

## 设计

### 输入和停止语义

- 原生层新增拥有像素生命周期的 `SuperResRgbImage`。stbi 解码指针由自定义 deleter 管理；Bitmap 只执行一次必要的 RGB888 转换，AUTO Vulkan 到 CPU 回退复用同一输入。
- Kotlin 新增 `InferenceStopReason.USER_REQUEST` 和 `THERMAL_CRITICAL_PAUSE`。用户取消继续清理 native cache，热暂停保留模型缓存；并发时用户取消优先。

### 输出传输

- Extreme 使用任务级 `NativeExtremePngSession`，按严格行优先的矩形行带直接写入原子 PNG，不再为活动路径生成 `.rgb` 瓦片文件。
- 行带预算为 Low-RAM/可用内存低于 768MB 时 16MiB，未知或 768MB–1.5GB 时 32MiB，至少 1.5GB 时 64MiB，绝对上限 64MiB。
- 多阶段推理通过 `MemoryRgbOutput` 在 native 内传递完整 RGB8 中间图，保持现有 PNG round-trip 的 8 位量化语义。中间传输预算分别为 16MiB、48MiB、96MiB，硬上限 96MiB；超限回退现有中间 PNG。
- 保留 `RAW_CROPPED_RGB_TILE` 和旧 merger 一个兼容周期，不迁移或删除现有 work-spec 枚举。

### Vulkan、校准和 TTA

- 优化 Vulkan 路径使用 GPU producer 和单个 CPU consumer 的双 slot 有界流水线。JNI 回调只在 producer 线程执行；consumer 独占后处理、RGB 拷贝和 PNG writer。
- depth 2 只允许 OPTIMIZED、Vulkan、Normal/Light、非 Low-RAM，并且两套 slot 估算同时满足 GPU heap 的 25%、可用 RAM 的 15% 和 128MiB 硬上限。未知或溢出时使用 depth 1。
- 校准按输出像素划分 SMALL `<8MP`、STANDARD `8–24MP`、LARGE `>24MP`。先筛 tile，再筛 batch；CPU profile 独立筛 tile、线程数、OpenMP blocktime 和 postprocess threads。只有至少快 5% 才选择更激进候选。
- TTA 未通过专属质量校准时固定使用 LEGACY_SAFE/FP32/batch1/depth1；通过 SSIM `>=0.995`、PSNR `>=40dB`、P99 通道误差 `<=3` 后，才允许每瓦片两个 D4 pass 的 Vulkan micro-batch。融合继续在 FP32 canonical 空间完成。

## 可靠性边界

- 任意失败、取消、OOM、Vulkan fallback 或 I/O 错误不得推进未完成的 output target，也不得发布 `.part`。
- GPU 与 CPU 线程共享一次性取消 latch；consumer 不读取 JNI runtime control。
- 中间 PNG、raw fallback 和 session 句柄均在 `finally`/RAII 中清理。
- 新路径初始化失败或校准未通过时自动回退，不改变当前可用功能。

## 验收

- 零拷贝、Extreme session、内存 pipeline、depth 1/2 的解码像素必须与稳定路径一致。
- 新 Extreme 活动路径 `.rgb` 字节数为零；合格多阶段任务中间 PNG 数量为零。
- 真机启用门槛：Vulkan depth 2 与优化 TTA 至少快 10%，Extreme `>=64MP` 至少快 15%，既有场景不得回退超过 3%，第三次/第一次耗时不超过 1.20。
- 峰值 PSS/graphics heap 增量不超过基线 25% 且绝对不超过 128MiB。

