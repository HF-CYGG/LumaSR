# LumaSR 真机性能基准

`benchmark` 模块通过 AndroidX Macrobenchmark 和 UIAutomator 驱动正式 App 路径。输入是测试 APK 在本地生成的规则图案，经系统 Photo Picker 导入；不会读取、上传或记录私人图片，也没有生产环境专用的推理开关。

## 基准变体

- 目标 App：`benchmark`，由 `release` 派生、R8/资源压缩保持开启、`debuggable=false`、使用 debug key 签名。
- 测试 APK：`com.android.test`，仅包含 Macrobenchmark 1.4.1、AndroidX Test 和 UIAutomator 依赖；生产 APK 没有新增运行时依赖。
- `app/src/benchmark/AndroidManifest.xml` 只为 benchmark 变体开启 shell profiling。
- 最低基准系统为 Android 10（API 29）；App 自身仍保持 `minSdk 26`。

## 场景

| 场景 ID | 合成输入 | 模型 | 倍率 | TTA |
| --- | ---: | --- | ---: | --- |
| `realcugan_12mp_2x` | 4000 × 3000（12 MP） | `realcugan-standard` | 2x | 关闭 |
| `waifu2x_4mp_2x` | 2000 × 2000（4 MP） | `waifu2x-cunet` | 2x | 关闭 |
| `animevideo_4mp_2x` | 2000 × 2000（4 MP） | `realesrgan-animevideo-x2` | 2x | 关闭 |

每个场景都有 cold/warm 两个测试方法，并固定使用 `CompilationMode.Full()`，不把 Macrobenchmark 的编译预热误当成 App/native 预热。cold 在每次场景准备前终止目标进程并记录 3 次；warm 先显式终止目标进程，完整执行 1 次 `phase=warmup_discarded` 的冷 App/native 样本但不纳入统计，随后在保留进程和 native 模型缓存的条件下恰好记录 3 次 `phase=measured`。optimized 校准会执行推理，因此 harness 会在校准完成后再次终止并重启目标进程，保证被丢弃的 warm 首样本确实从冷 native cache 开始。建议一次只运行一个方法，避免长时间连续运行互相引入热量和存储干扰。

默认 profile 为 `optimized`：harness 在计时区间外通过正式“设置 → 本机性能校准”UI 为当前场景模型执行本机校准，等待“已校准，高性能模式可用”，再逐次硬校验 native `backend=1`（OPTIMIZED）与 `precision=1`（FP16）。校准失败、identity 不匹配或运行时回退都会令测试失败，不能把 legacy 数据误标成优化结果。`legacy` profile 不校准，并硬校验 `backend=0/precision=0`，用于同设备基线。

## 构建

```powershell
.\gradlew.bat -g F:\Development\gradle :app:assembleBenchmark :benchmark:assembleBenchmark
```

## 真机运行

先确认唯一目标是需要验收的物理设备：

```powershell
adb devices -l
```

下面以 RealCUGAN warm 为例：

```powershell
adb logcat -c
.\gradlew.bat -g F:\Development\gradle :benchmark:connectedBenchmarkAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.lumasr.benchmark.SuperResolutionMacrobenchmark#realCugan12Mp2xWarm `
  -Pandroid.testInstrumentationRunnerArguments.lumasrBenchmarkProfile=optimized
adb logcat -d -s LumaSRBenchmark:I LumaSRNative:I "*:S" > benchmark-realcugan-warm.log
```

其余方法：

- `realCugan12Mp2xCold`
- `waifu2x4Mp2xCold` / `waifu2x4Mp2xWarm`
- `animeVideo4Mp2xCold` / `animeVideo4Mp2xWarm`

系统 Photo Picker 的布局由 ROM 提供。测试必须先进入 `LumaSRBenchmark` 专用相册，并确认页面公开了不可点击的同名标题，之后才按精确文件名选择合成图。若 ROM 不展示文件名，仅当该已确认相册内恰好存在一个系统缩略图控件时才允许选择；不会在“最近照片”、未确认页面或多个候选项中使用控件几何位置盲选。无法确认专用相册或唯一候选时测试会失败，不会跳过真实导入路径。仍应使用没有私人媒体的专用测试用户，并在运行期间停止相机/截图同步，避免 ROM 媒体索引异常造成误选。

运行 `legacy` 对照时，把参数改为 `-Pandroid.testInstrumentationRunnerArguments.lumasrBenchmarkProfile=legacy`。优化与基线日志必须分别显示 `requestedProfile=CALIBRATED_AUTO_GPU` 和 `requestedProfile=LEGACY_AUTO_GPU`，且 native backend/precision 与之对应。每个计时样本都只接受清空 logcat 后唯一 task 的唯一 `region=-1 retry=0` 最终记录，并要求 `modelKey` 与场景、`driverId/driverVersion` 与运行设备完整匹配；缺字段、跨 task、区域记录或重试记录都会令该轮失败。测试结束会严格检查生成媒体删除结果以及 `pm clear com.lumasr` 返回 `Success`，所以每个 optimized 测试方法都会为自己的 model/driver identity 重新校准，不会复用上一个模型的结果；清理失败会保留可诊断错误而不是被吞掉。

## 输出格式

`LumaSRBenchmark` 会输出稳定、空格分隔的 `key=value` 记录：

```text
LUMASR_BENCHMARK schema=1 phase=warmup_discarded scenario=realcugan_12mp_2x modelId=realcugan-standard requestedProfile=CALIBRATED_AUTO_GPU cache=warm iteration=0 elapsedMs=... profileIdentity=CALIBRATED_AUTO_GPU:1:1 calibrationIdentity=realcugan-standard:realcugan-standard:...:... gpuDriverId=... gpuDriverVersion=... task=... modelKey=realcugan-standard region=-1 retry=0 ...
LUMASR_BENCHMARK schema=1 phase=measured scenario=realcugan_12mp_2x modelId=realcugan-standard requestedProfile=CALIBRATED_AUTO_GPU cache=warm width=4000 height=3000 tta=false iteration=1 elapsedMs=... manufacturer=... deviceModel=... device=... sdk=... abi=... buildId=... fingerprintSha256=... profileIdentity=CALIBRATED_AUTO_GPU:1:1 calibrationIdentity=realcugan-standard:realcugan-standard:...:... gpuDriverId=... gpuDriverVersion=... task=... modelKey=realcugan-standard driverId=... driverVersion=... region=-1 retry=0 backend=1 precision=1 batch=... decodeMs=... modelLoadMs=... inferenceMs=... postprocessMs=... encodeMs=... totalMs=...
LUMASR_BENCHMARK schema=1 phase=summary scenario=realcugan_12mp_2x ... samples=3 medianMs=... geometricMeanMs=... thirdVsFirst=... profileIdentity=... calibrationIdentity=... gpuDriverId=... gpuDriverVersion=...
```

`fingerprintSha256` 用于区分系统镜像但不直接暴露完整 build fingerprint。`profileIdentity` 绑定请求 profile 与实际 backend/precision；optimized 的 `calibrationIdentity` 绑定模型 ID、native `modelKey`、GPU driver ID 和版本。native 字段来自 `LumaSRNative` 的唯一最终 `Performance` 记录，可确认 task、模型实现、驱动、region/retry、实际 backend、precision、batch、fallback 与分阶段耗时。三个 measured 样本的 runtime identity 必须完全一致。Macrobenchmark 结果目录还包含帧时序和 Perfetto 证据。

## 验收计算

对同一台命名物理设备、相同电量/温控/系统设置和相同 cache 模式，分别用 `legacy` 与 `optimized` 保存三次 `elapsedMs`。若使用历史 baseline build，则应把同一 benchmark harness 应用到该 build，并确认其输出仍是 `LEGACY_SAFE/FP32`；不得通过取消 optimized 校验来冒充优化结果。

1. 每个场景的代表耗时取三次测量的中位数。
2. 场景加速比：`baselineMedianMs / optimizedMedianMs`，三个场景均须 `>= 1.15`。
3. 总体几何平均加速比：`(speedup1 * speedup2 * speedup3)^(1/3)`，须 `>= 1.50`。
4. 热稳定性：每个优化场景 `iteration3.elapsedMs / iteration1.elapsedMs <= 1.20`。

`phase=summary` 中的 `medianMs`、`geometricMeanMs` 和 `thirdVsFirst` 用于复核单个构建；跨构建 speedup 仍必须使用两份原始日志计算。模拟器结果只用于流程诊断，测试不会在模拟器上做硬性能断言，也不能作为手机厂商/OEM 性能结论。

## 结果记录清单

- 设备厂商、型号、系统版本、ABI、build ID、fingerprint 哈希、GPU driver ID/版本。
- 测试方法、Git commit、cold/warm、`profileIdentity` 与 `calibrationIdentity`；warm 另保留被明确丢弃的冷样本。
- 三次逐次 `elapsedMs`、中位数、第三次/第一次比例。
- native task、modelKey、region/retry、backend、precision、fallback、batch 和各 stage timing。
- Perfetto/Macrobenchmark 输出目录与 logcat 原始文件。
- 测试前后电量与热状态；若系统限频、来电、后台更新或界面定位失败，作废该轮并记录原因。
