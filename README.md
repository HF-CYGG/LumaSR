# LumaSR 本地 AI 图像超分工具

![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![ncnn](https://img.shields.io/badge/ncnn-20260526-black)
![Vulkan](https://img.shields.io/badge/Vulkan%2FCPU-Ready-A41E22?logo=vulkan&logoColor=white)
![Offline](https://img.shields.io/badge/Offline-Inference-2EA44F)

LumaSR 是一款面向 Android 的本地 AI 图像超分工具，基于 ncnn 与 Vulkan/CPU 加速实现离线推理，支持 Waifu2x、RealCUGAN、Real-ESRGAN 等模型，提供极简参数控制、实时分块处理预览与前后画质对比体验。

项目目标是把常见超分模型做成可直接在手机端运行的轻量工具：不上传图片、不依赖云端服务，并尽量保留清晰、可维护的 Android 与 Native 分层。

## 功能特性

- 本地离线推理：图片与模型均在设备端处理。
- 内置模型包：随 APK 打包 Waifu2x、RealCUGAN、Real-ESRGAN 系列权重。
- ncnn Native 引擎：通过 JNI 调用 C++ 推理层，支持 `.param/.bin` 模型加载。
- Vulkan/CPU 模式：支持自动模式，GPU 不可用时可回退 CPU。
- 分块处理进度：按 tile 推进并向 Compose UI 回传阶段、百分比与 tile 状态。
- 前后对比：处理完成后显示输入与输出预览，可通过滑杆比较效果。
- 结果保存与分享：结果可保存到系统相册目录，并在保存后调用系统分享。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- MVVM + StateFlow + Coroutine
- Android Photo Picker 与 scoped storage
- JNI + CMake + C++17
- ncnn Android Vulkan SDK
- stb_image / stb_image_write

## 项目结构

```text
app/src/main/java/com/lumasr/   Compose UI、ViewModel、data/domain/processor
app/src/main/cpp/               JNI、CMake、ncnn 推理与分块处理
app/src/main/assets/            model_manifest.json 与内置模型文件
app/src/test/                   Manifest、参数映射、处理状态等 JVM 测试
docs/                           Native 推理和模型打包说明
scripts/download-deps.ps1       国内镜像依赖与模型安装脚本
```

## 内置模型

当前 APK 内置模型由 `app/src/main/assets/model_manifest.json` 管理：

| 引擎 | 模型 | 适用场景 |
| --- | --- | --- |
| Waifu2x | CUnet | 插画、动漫线稿、高质量降噪 |
| Waifu2x | Anime | 动漫截图、压缩插画 |
| Waifu2x | Photo | 普通照片柔和增强 |
| RealCUGAN | Standard | 干净插画与通用动漫图 |
| RealCUGAN | Pro | 压缩瑕疵修复与高质量增强 |
| Real-ESRGAN | x4plus | 真实照片与退化图片 4x 修复 |
| Real-ESRGAN | x4plus anime | 动漫、插画 4x 增强 |
| Real-ESRGAN | AnimeVideo v3 x2/x3/x4 | 动画帧和视频截图 2x/3x/4x 增强 |

模型文件来自上游开源项目或其 Android 资产包，仓库仅按清单打包运行所需文件。具体下载、校验和来源约束见 [docs/builtin-models.md](docs/builtin-models.md)。

## 构建环境

- JDK 17
- Android SDK 34
- Android NDK `27.2.12479018` 或更新版本
- CMake 3.22.1
- Gradle 缓存建议使用 `F:\Development\gradle`
- 所有外部下载必须使用国内镜像或代理地址，避免直接访问 `github.com`、`dl.google.com`、`repo.maven.apache.org`、`services.gradle.org`

Native 依赖说明见 [docs/native-inference.md](docs/native-inference.md)。

## 快速开始

```powershell
# 校验内置模型和 native 依赖是否齐全
.\scripts\download-deps.ps1 -ValidateOnly

# 运行 JVM 单元测试
.\gradlew.bat -g F:\Development\gradle testDebugUnitTest

# 构建 Debug APK
.\gradlew.bat -g F:\Development\gradle assembleDebug
```

构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 验证命令

```powershell
.\gradlew.bat -g F:\Development\gradle testDebugUnitTest
.\gradlew.bat -g F:\Development\gradle lintDebug
.\gradlew.bat -g F:\Development\gradle assembleDebug
```

建议在真机或模拟器上手动验证：选择图片、切换模型与参数、开始处理、取消处理、完成后进入对比页、保存结果、深色模式下控件不遮挡。

## 后续计划

- 增加批量队列和历史记录。
- 提供更细的 tile 大小、线程数和显存策略控制。
- 补充不同机型上的 Vulkan 兼容性与性能基准。
- 完善发布签名、版本更新说明和正式包分发流程。

## 许可与来源

项目代码遵循 [LICENSE](LICENSE)。ncnn、Waifu2x、RealCUGAN 及模型权重遵循各自上游项目许可；分发或二次打包前请确认对应模型来源、授权和署名要求。
## Model Update: Real-ESRGAN

LumaSR now exposes additional built-in Real-ESRGAN models through `model_manifest.json`:

- `Real-ESRGAN x4plus`: general real-world photo restoration.
- `Real-ESRGAN x4plus anime`: anime and illustration enhancement.
- `Real-ESRGAN AnimeVideo v3 x2/x3/x4`: animation frame enhancement with 2x, 3x, and 4x variants.

The built-in scale list is also corrected for existing RealCUGAN assets:

- `RealCUGAN Standard`: `2x / 3x / 4x`
- `RealCUGAN Pro`: `2x / 3x`

Use `scripts/download-deps.ps1 -RealEsrganModelsZipUrl "<domestic-mirror-zip>"` to install the Real-ESRGAN `.param/.bin` files. `-RealEsrganZipUrl` is still accepted for compatibility. Direct GitHub download URLs remain blocked by the script; use a domestic mirror or proxy URL.
