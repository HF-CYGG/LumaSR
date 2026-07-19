# Snapdragon QNN/HTP 深度优化设计

## 目标

在当前 `codex/performance-next` 分支为 LumaSR 增加可校准、可回退的 Qualcomm
HTP 推理后端。QNN 只有在设备自检、像素质量和持续性能门全部通过后才进入 AUTO；
现有 ncnn Vulkan/CPU 路径始终保留，不改变固定签名、存储权限或当前 `minSdk 29`。

## 运行时架构

- `AccelerationMode` 追加 `QNN_HTP`，旧序号不变。AUTO 可以选择已发布的 QNN
  profile；显式 Vulkan 和 CPU 保持现有语义。
- QAIRT 通过窄函数表动态装载。没有 SDK、非 arm64、API 低于 30、HTP/Skel
  不匹配或金丝雀执行失败时，能力快照返回不可用，不影响普通构建和 ncnn 处理。
- QNN 处理复用现有瓦片几何和 `RgbOutputTarget`，使用固定 core tile、边缘填充、
  有序输出和原子最终发布。取消、热暂停、失败或回退不得留下部分结果。
- context 按 QAIRT build、HTP 架构、Skel、DLC、模型、精度和 tile 哈希隔离；
  采用同目录临时文件和原子替换，损坏只重建一次，总量按 LRU 限制为 512MiB。
- 两个注册共享缓冲 slot 组成有界异步流水。只有估算不超过可用 RAM 15% 且
  绝对不超过 128MiB 时使用 depth 2，否则固定 depth 1。

## 模型和质量门

首批候选为 Real-ESRGAN AnimeVideo x2、Anime x4、General x4 和 RealCUGAN
Standard 默认 2x/降噪 1。每个候选生成 FP16/A16W8 与 128/256 core tile 图。
存在未支持算子或 CPU 分区时拒绝发布该 DLC。

A16W8 量化语料通过 `LUMASR_QNN_CALIBRATION_CORPUS` 外置提供；仓库只记录
许可、输入清单哈希、裁剪规则和指标。候选相对 ncnn FP32 必须满足：

- SSIM 不低于 0.995；
- PSNR 不低于 40dB；
- P99 通道误差不高于 3；
- 尺寸、裁剪和 tile 接缝检查全部通过。

## 校准和热策略

手动校准卡片显示步骤、预计剩余时间和真实日志，并持久化最终结果、完整日志和
失败负证据。每个候选预热 3 次，正式测量至少 30 个样本且持续至少 60 秒；
QNN 稳态中位数至少比设备当前最快 ncnn profile 快 15%，且 P95 不回退时才发布。

- NORMAL/LIGHT：持续高性能，内存允许时 depth 2；
- MODERATE：平衡模式，depth 1；
- SEVERE：低功耗模式，depth 1；
- CRITICAL：下一瓦片前停止，由 Runner 映射为热暂停重试。

用户取消优先于热暂停。API 31+ 仅对 CPU feeder/consumer 使用 ADPF
Performance Hint；不设置 CPU affinity，不写 sysfs。

## 构建和分发

QAIRT 2.46.x、模型源权重和授权语料位于仓库外。WSL2 Ubuntu 负责导出、量化和
DLC 生成；Windows Gradle 构建通过锁文件校验全部输入与产物哈希，并只把许可允许
的最小 Stub、Skel 和 DLC 放入单一离线 APK。SDK 工具、头文件、语料、密钥和用户
路径不得进入 APK 或日志。固定 Release 脚本继续校验证书 SHA-256。

当前设备离线且 SDK、WSL、语料未就绪时，只能完成领域契约、假运行时、构建门、
持久化、校准策略和安全回退；不得宣称 HTP 真机提速或 OEM 兼容通过。
