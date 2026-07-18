# LumaSR 有界真实档位校准设计

## 背景与目标

REDMI K90 Pro Max 的 Adreno 840 已通过 Vulkan、FP16 和三轮真实推理验证，但 ncnn `gpuRoughScore=21` 被静态 `>=75` 条件误判为不可校准。移除入口误判后，校准又在 SMALL 档完成 FP32 基线及 tile 64/128 候选后停止。设备应用 Java 堆上限为 256MiB，而当前校准要求 SMALL、STANDARD、LARGE 分别保留至少 128MiB、256MiB、512MiB 可用 Java 堆；STANDARD 和 LARGE 因而不可能进入。

本修复需要让性能测量继续覆盖真实 `<8MP`、`8–24MP`、`>24MP` 输出，同时将 Java 侧质量比较限制在固定小窗口。校准结果仍必须经过质量、速度、设备身份、温控、有效期和原子持久化门槛，任何证据不完整时继续使用兼容模式。

## 方案选择

采用“有界质量样本 + 真实档位仅测量”方案：

- FP16 质量门使用最大 512×512 输出的固定合成样本，比较完整样本的 SSIM、PSNR 和 P99 通道误差。
- Vulkan 和 CPU 性能候选仍处理真实分辨率档位，但完成后只读取输出 PNG 的宽高和处理耗时，不把完整输出解码为 `RgbImage`。
- ncnn 粗略分数保留在设备身份与诊断数据中，不再覆盖已通过真实质量和速度校准的结论。

不采用以下方案：

- 不通过 `android:largeHeap` 扩大 Java 堆；该方案无法满足 512MiB“可用堆”条件，也违反有界内存目标。
- 不把 SMALL 档配置直接复制为未测量的 STANDARD/LARGE 配置；缺少真实档位证据时不得声明对应档位已校准。
- 不缩小性能工作量伪装成真实分辨率档位。

## 接口与数据流

### 探针接口

保留 `InferenceCalibrationProbe.run(input, params)` 作为质量输出入口，并新增：

```kotlin
data class CalibrationProbeMeasurement(
    val outputWidth: Int,
    val outputHeight: Int,
    val durationNanos: Long
)

suspend fun InferenceCalibrationProbe.measure(
    input: RgbImage,
    params: UpscaleParams
): CalibrationProbeMeasurement?
```

接口提供从旧 `run()` 结果转换的默认实现，使现有测试探针和非 Android 实现保持兼容。`NativeBitmapCalibrationProbe` 覆盖 `measure()`：执行同一原生处理，但使用 `BitmapFactory.Options.inJustDecodeBounds` 验证 PNG 尺寸，不进行像素解码。

质量路径继续返回 `CalibrationProbeOutput`，但只接收有界合成输入。性能路径只返回尺寸和耗时。

### 校准顺序

1. 生成有界质量样本，依次运行 LEGACY_SAFE/FP32 与 OPTIMIZED/FP16。
2. 完整比较质量样本；不满足 SSIM `>=0.995`、PSNR `>=40dB`、P99 `<=3` 时停止并返回 `QUALITY_REJECTED`。
3. 按 SMALL、STANDARD、LARGE 顺序生成真实档位输入。每个档位先测 FP32 基线，再测 FP16 tile，最后测 batch；每个候选必须输出正确尺寸且至少比当前候选快 5%。
4. CPU profile 在 Vulkan profile 后独立测量真实档位。CPU 始终使用 LEGACY_SAFE/FP32；如果总时间预算不足则整个校准不发布，而不是写入未测量配置。
5. 支持 TTA 的模型继续单独比较 FP32 与 FP16 质量；速度测量使用仅尺寸模式，未达到 10% 提速时不写入优化 TTA profile。
6. 三档数据齐全后一次性原子发布 schema v4 校准记录。

## 内存与时间边界

- 质量输出边长硬上限为 512，单个 RGB 样本最多 768KiB。
- 真实档位输出禁止完整 Java 解码；只允许 PNG bounds、输入 RGB、输入 Bitmap、单行转换缓冲和原生有界 scratch 同时存在。
- `NativeBitmapCalibrationProbe` 输入 Bitmap 转换改为逐行写入，避免整图 `copyBytes + IntArray + Bitmap` 三份并存。
- 校准堆预算按真实输入 staging 与固定质量窗口估算，SMALL、STANDARD、LARGE 上限分别不超过 64MiB、96MiB、128MiB。
- 保留五分钟总超时。候选顺序从保守到激进；到期、Moderate 及以上温控、OOM、取消或输出错误均停止且不发布部分结果。

## 优化启用条件

普通 Vulkan FP16 仅在以下条件全部满足时启用：

- Vulkan、FP16 storage 和 FP16 arithmetic 可用；
- GPU vendor、device、driver 身份完整且与校准记录一致；
- 模型、运行时构建、设备指纹和驱动身份与未过期记录一致；
- FP16 质量门通过；
- 当前分辨率档位存在真实测量所得 profile。

`gpuRoughScore` 不再作为已校准设备的额外否决条件。没有有效校准时仍使用 LEGACY_SAFE/FP32。TTA 继续要求自己的质量与至少 10% 提速证据。

## 错误处理与可观测性

- 输出尺寸、耗时或质量数据无效时返回类型化失败，不发布记录。
- 临时 PNG 在成功、失败、取消和异常路径统一 `finally` 删除。
- 日志只记录校准阶段、失败原因、档位、tile、batch、耗时、质量数值和内存计数，不记录图片路径或用户文件信息。
- UI 对用户显示可理解的失败类别；兼容模式始终可用。

## 测试与验收

- JVM 测试覆盖 `measure()` 默认兼容、bounds-only 原生探针、错误尺寸、临时文件清理和取消。
- Runner 测试覆盖质量只运行一次、三档性能使用真实边界、256MiB Java 堆可进入三档、质量失败、温控、超时、OOM 和无部分发布。
- Policy 测试覆盖粗略分数 21 在有效校准下可启用、无校准仍为安全路径、TTA 仍需专属证据。
- 完整执行单测、Lint、Debug/Release native 和固定签名 Release 构建；证书 SHA-256 必须保持 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`。
- 在 REDMI K90 Pro Max 上覆盖安装，重跑校准并记录耗时、热状态、PSS、profile 与后续真实处理 backend。只有真机校准成功且处理日志实际使用 OPTIMIZED/FP16 时，才能声明该设备启用优化。

## 固定约束

- 不新增依赖、权限或 `largeHeap`，不改变模型、质量门、`minSdk`、固定 APK 签名和用户数据。
- 保留稳定路径和自动回退；失败时不得发布部分输出或校准记录。
- 不推送远端、不合并 `main`，完成后停留在 `codex/performance-next`。
