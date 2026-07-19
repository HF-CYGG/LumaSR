# GitHub Release 更新检查设计

## 目标

LumaSR 在前台启动时自动检查 GitHub 最新正式 Release，并在设置页支持手动检查。发现高于本机 `versionName` 的版本后，展示版本号、纯文本更新说明、国内加速下载和 GitHub 原始下载入口。更新检查与 APK 分发不使用自有服务器，Gitee 不参与版本或 APK 链路。

## 发布契约

- 唯一元数据源为 `https://api.github.com/repos/HF-CYGG/LumaSR/releases/latest`。
- 当前遗留 `release` 标签只作为历史兼容；客户端将它视为当前无可比较更新，不显示错误弹窗。
- 从下一版本开始，每个正式版本创建不可变 GitHub Release 标签，例如 `v0.3.2`、`v0.3.3`。
- 正式 APK 固定命名为 `LumaSR.apk`；旧 Release 中唯一有效的 `.apk` 可作为兼容资产。
- 客户端直接使用 API 返回的 `assets[].browser_download_url`，不手工拼接版本标签。
- 国内下载地址为 `https://gh.jasonzeng.dev/` 加经过白名单验证的 GitHub APK URL；同时保留 GitHub 原始地址。
- Release APK 必须延续固定证书签名，`versionCode` 严格递增，并在发布前通过 `apksigner verify`。

## 架构

- `AppVersion` 解析 `v0.3.2`、`0.3.2` 以及最多四段数字版本并逐段比较，禁止字符串比较。
- `LatestReleaseSource` 是领域层读取接口；`GitHubReleaseSource` 使用 `HttpURLConnection` 在 `Dispatchers.IO` 请求公开 API，不携带 Token。
- `GitHubReleaseParser` 使用项目已有 `kotlinx-serialization-json` 解析 Release 和 APK 资产，并验证 HTTPS、`github.com`、仓库路径、上传状态、文件大小和 `.apk` 后缀。
- `CheckForUpdateUseCase` 比较本机和远端版本，返回可更新或已是最新；遗留 `release` 标签映射为已是最新。
- 独立 `UpdateViewModel` 管理自动检查节流、手动检查、设置开关、错误可见性和更新弹窗，不把更新状态并入图片处理状态。
- `UpdatePreferencesRepository` 使用独立 `SharedPreferences` 保存自动检查开关和最近一次自动尝试时间。

## 自动检查与错误行为

- `MainActivity.onStart()` 触发机会式自动检查；默认开启，每 24 小时最多尝试一次，不使用 WorkManager 或后台常驻任务。
- 自动失败保持静默并记录本次尝试时间，避免每次进入前台重复请求。
- 设置页手动检查不受 24 小时限制，并展示检查中、已是最新、发现更新或失败状态。
- `404` 表示尚无正式 Release；手动检查显示明确提示，自动检查静默。
- 网络、JSON、标签或资产异常不重试，不暴露底层异常细节。

## UI

- 设置页新增“软件更新”分组：自动检查开关和“检查更新”行。
- 更新弹窗位于主界面根层，显示版本、纯文本更新说明、国内加速、GitHub 原始下载和稍后操作。
- 打开下载链接使用系统浏览器；App 不下载、不静默安装、不申请未知来源安装权限。
- 隐私说明明确：图片始终只在本机处理，更新检查仅请求 GitHub 版本元数据；使用国内加速时第三方服务会接触下载请求。

## 安全边界

- APK 内不嵌入 GitHub/Gitee Token。
- 仅接受 `https://github.com/HF-CYGG/LumaSR/releases/download/.../*.apk`。
- 加速前缀只作用于已校验的 `browser_download_url`。
- Release 说明只作为 Compose `Text` 展示，不使用 WebView 或 HTML。
- GitHub 请求设置连接和读取超时，不记录用户图片、文件路径或设备性能数据。

## 验证

- JVM 单测覆盖三/四段版本比较、遗留标签、Release JSON、资产选择、URL 白名单、加速链接、自动检查节流和用例结果。
- 设置页静态测试覆盖自动检查开关、手动检查入口与隐私文案。
- 运行目标测试后扩大到全量 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`。
- 无真机或无真实新 Release 时，明确保留浏览器跳转和真实网络链路的手动验证缺口。
