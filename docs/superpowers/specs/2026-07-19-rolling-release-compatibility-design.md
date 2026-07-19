# 固定 `release` 标签更新兼容设计

## 背景与目标

GitHub 仓库当前只有一个已发布 Release，并使用固定标签 `release`。继续复用该标签时，下载地址会稳定包含 `/releases/download/release/`，但标签本身不再表达应用版本，因此客户端不能继续只解析 `tag_name`。

本设计让客户端兼容两类发布：

- 常规不可变发布：标签为 `v0.3.2`、`v1.0.5.1` 等数值版本；
- 滚动发布：标签固定为 `release`，真实版本写在 Release 标题中。

不新增服务器、运行时依赖或 Gitee APK 分发。更新元数据仍只读取 GitHub 公共 API。

## 版本解析规则

客户端按以下优先级解析版本：

1. 先尝试把 `tag_name` 解析成 2 至 4 段数值版本；成功时直接使用标签版本。
2. 仅当 `tag_name` 严格等于 `release` 时，才允许从标题解析版本。
3. 滚动发布标题只接受 `v0.3.2` 或 `LumaSR v0.3.2` 这两类完整格式；允许大小写差异和首尾空白，不允许附加 `hotfix`、日期等自由文本。
4. 标签与标题均不能解析时，更新检查失败并显示既有错误状态，不把未知版本误判为新版本或最新版本。

数值标签优先可以保留未来恢复多标签 Release 的兼容性；严格标题格式可以避免正文式标题中的数字被误识别。

## 下载资产规则

- 客户端不拼接标签或文件名，始终使用 GitHub API `assets[].browser_download_url` 返回的完整 URL。
- URL 必须是 `https://github.com/HF-CYGG/LumaSR/releases/download/.../*.apk`，通过仓库与协议校验后才能生成国内加速地址。
- 滚动 Release 必须只保留一个有效 APK。本版本使用 `LumaSR-v0.3.2.apk`，后续版本按 `LumaSR-v<version>.apk` 命名。
- 更新同一 Release 时先删除旧 APK，再上传新 APK；SHA-256 文件和 JSON 清单不属于 APK，可以一并保留当前版本，但旧版校验文件也应删除以避免混淆。

只保留一个 APK 是必要约束：客户端可以接受任意单一 APK 文件名；多个非规范 APK 会被判为歧义并拒绝更新。

## 发布操作规则

GitHub 不会为相同标签创建第二个 Release。发布者必须编辑现有 `release` Release：

1. 将标题严格改为 `LumaSR v0.3.2`；
2. 替换正文为 `docs/releases/v0.3.2.md`；
3. 删除旧 APK 与旧校验附件；
4. 上传 `LumaSR-v0.3.2.apk`、对应 `.sha256` 和 `release-manifest.json`；
5. 保持正式版、非预发布并设为 Latest；
6. 更新 `release` 标签指向已验证、已合并的发布提交。

编辑 Release 不会自动移动 Git 标签；标签移动属于强制更新远端引用，必须单独确认并在发布代码已合并后执行。

## 本地发布物

保留现有不可变版本构建目录 `artifacts/releases/v0.3.2/`，另生成滚动发布暂存目录：

```text
artifacts/releases/release/LumaSR-v0.3.2.apk
artifacts/releases/release/LumaSR-v0.3.2.apk.sha256
artifacts/releases/release/release-manifest.json
```

滚动发布 APK 只是已验证固定签名 APK 的字节级副本，不重新签名。发布契约校验器接受显式的预期资产名，并继续验证包名、`versionName`、`versionCode`、固定证书与 ZIP 对齐。

## 测试与失败边界

- 单元测试覆盖固定标签标题升级、已安装同版本、非法滚动标题、常规数值标签和版本化单一 APK URL。
- 发布工作流契约测试覆盖滚动发布脚本、版本化文件名、`release` 清单标签与显式资产名校验。
- 构建阶段运行 JVM 单元测试、Android Lint、Release 构建、`apksigner verify`、`aapt2 dump badging` 和 `zipalign -c`。
- 本任务只准备本地代码、文档和签名发布物，不修改线上 Release、不移动标签、不推送。

## 已知权衡

固定标签方案会覆盖历史附件，GitHub 页面无法为每个版本保留独立下载资产，CDN 和用户缓存也需要依赖版本化文件名规避。客户端保留数值标签优先逻辑，以便未来迁回每版本独立标签而无需再次改代码。
