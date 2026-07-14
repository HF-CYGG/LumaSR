# Android / 国内 ROM 兼容性验收矩阵

LumaSR 只使用 Android 公共 API，不按厂商名称切换推理实现。设备能力、Vulkan/FP16
可用性、内存与热状态均在运行时探测；未知、探测失败、低内存或校准失败时必须保持
legacy FP32 安全路径。厂商 ROM 的结论必须来自对应物理设备记录，不能由模拟器结果代替。

## 发布前设备覆盖

至少在下列 ROM 的当前主版本和仍受支持的上一主版本各选择一台设备；高端组优先
Snapdragon 8 系与 Dimensity 9 系，兼容组至少包含一台 4–6 GB 内存设备。

| 优先级 | 厂商 / ROM | 重点风险 |
| --- | --- | --- |
| P0 | Xiaomi / HyperOS、MIUI | 后台启动限制、进程回收、系统 Photo Picker、Vulkan 驱动 |
| P0 | OPPO / ColorOS、OnePlus | 前台服务限制、通知权限关闭、温控降频、MediaStore |
| P0 | vivo / OriginOS、Funtouch OS | 后台冻结、前台服务重建、Photo Picker 差异、GPU 驱动 |
| P1 | HONOR / MagicOS | 后台回收、内存压力、Vulkan/CPU 回退 |
| P1 | HUAWEI / HarmonyOS（Android 兼容版本） | Vulkan 能力缺失、系统文件选择器、后台限制 |
| P1 | Samsung / One UI、Google / AOSP | Android 平台基线、最新 API 行为 |
| P2 | realme UI、Motorola、ASUS 等 | OEM 定制后台策略与文件选择器差异 |

系统版本至少覆盖：API 26–28、API 29–32、API 33–34 和 API 35。API 35 使用
`mediaProcessing` 前台服务及超时回调；API 29–34 使用 `dataSync` 类型；更低版本使用
兼容的前台服务调用。所有版本都不得请求广泛存储权限。

## 每台设备的必测流程

1. 首次启动并拒绝通知权限，使用系统 Photo Picker 导入单图和多图；不得出现权限死循环。
2. 跳过校准后处理图片，确认实际 profile 为 legacy FP32；校准超时、取消、输出损坏时同样如此。
3. 在支持的高端机完成本地合成图校准，确认只有质量和速度均通过后才启用 optimized FP16。
4. 分别处理 RealCUGAN 12 MP 2x、Waifu2x 4 MP 2x、anime/video 4 MP 2x；记录 native stage timing。
5. 处理期间切到后台、锁屏、从最近任务划掉 UI，并在系统允许的情况下重启进程；任务不得重复执行，
   恢复后输出只能是完整文件。
6. 禁止 ROM 启动前台服务或在后台触发启动，确认仅在宿主界面处于前台时启用进程内 fallback；
   Service 与 fallback 不得同时持有 native 执行权。
7. 在 moderate、severe、critical 热状态下验证批量和 headroom 收缩；critical 不得启动新的 native 工作，
   降温后允许用户重试。
8. 在低内存、存储空间不足、Vulkan 初始化失败和 GPU tile 输出不一致条件下验证 CPU/安全路径或
   可重试失败；不得崩溃、ANR、泄漏 `.part` 文件或发布条纹/半张图。
9. 完成保存、分享、前后对比和历史记录流程；文件名、方向、颜色和透明/边缘区域应正确。
10. 连续执行至少三次同一场景，第三次耗时不得比第一次高 20% 以上；同时检查机身热状态和系统降频。

## 通过条件与证据

- 全流程无崩溃、ANR、重复 native 推理、永久 RUNNING 任务和不可恢复的队列阻塞。
- 校准记录必须匹配 schema、设备 profile、Build fingerprint 哈希、GPU driver、模型与引擎；系统或驱动
  更新后旧记录不得启用 FP16。
- 每个任务的输出通过原子发布；失败或取消不得留下可被图库识别的不完整最终文件。
- Macrobenchmark 的三个优化场景分别达到 `>= 1.15x`，总体几何平均达到 `>= 1.50x`。数值只在命名的
  物理设备、相同电量/温控/系统设置和相同校准状态下比较。
- 保存设备厂商、型号、ROM/Build ID、API、ABI、fingerprint SHA-256、GPU driver、实际 backend、
  precision、batch、fallback、三次耗时和 Perfetto/logcat 文件；不得记录用户图片路径或内容。

构建与测量命令见 [performance-benchmark.md](performance-benchmark.md)。没有对应真机证据的厂商/ROM
只能标记为“待验证”，不能标记为“已兼容”。
