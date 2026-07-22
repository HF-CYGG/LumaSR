# LumaSR 正式发布流程

本文档定义当前固定 `release` 标签方案。GitHub Release 负责公开版本元数据与 APK 分发；构建、签名和完整校验均在本机完成，不占用自建服务器，也不把签名材料交给 GitHub Actions。

## 当前发布契约

- GitHub 仓库为 `HF-CYGG/LumaSR`，正式 Release 的固定标签为 `release`。
- Release 标题必须严格为 `LumaSR v<version>`，例如 `LumaSR v0.3.4`。
- `app/build.gradle.kts` 的 `versionCode` 必须递增，`versionName` 使用 2 至 4 段数字。
- Release 只保留一个 APK，命名为 `LumaSR-v<version>.apk`；校验文件使用同名 `.sha256`。
- APK 必须使用固定证书签名，证书 SHA-256 为 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`。
- Release 必须是正式版、非草稿、非预发布，并设置为 Latest。
- 客户端直接读取 `/releases/latest` 的 `tag_name`、`name` 和 `assets[].browser_download_url`，不拼接标签或文件名。
- 数值标签优先；只有 `tag_name=release` 时，客户端才从严格格式标题解析版本。

固定标签意味着历史附件会被覆盖，不能像独立版本标签那样在 GitHub Release 页面保留每一版二进制。版本化文件名用于降低 CDN 与浏览器缓存命中旧 APK 的风险。

## 1. 准备版本与新闻稿

1. 递增 `versionCode` 并设置新的数值 `versionName`。
2. 更新 `docs/releases/v<version>.md`，一级标题和 GitHub Release 标题均使用 `LumaSR v<version>`。
3. 运行不可变版本元数据与滚动标签元数据检查：

```powershell
.\scripts\validate-release-metadata.ps1 -TagName v0.3.4
.\scripts\validate-rolling-release-metadata.ps1 -TagName release
.\scripts\test-release-workflow.ps1
```

## 2. 构建固定签名滚动发布物

签名环境继续保存在被 Git 忽略的 `artifacts/signing` 中。不得复制、提交、打印或上传私钥和密码。

```powershell
.\scripts\prepare-rolling-release.ps1 `
  -ReleaseVersion 0.3.4 `
  -GradleUserHome F:\Development\gradle
```

脚本先调用固定签名构建，执行 JVM 测试、Android Lint、Release 构建、证书校验、包版本校验和 ZIP 对齐校验；然后把已验证 APK 复制为滚动发布文件，并再次验证。最终生成：

```text
artifacts/releases/release/LumaSR-v0.3.4.apk
artifacts/releases/release/LumaSR-v0.3.4.apk.sha256
artifacts/releases/release/release-manifest.json
```

`artifacts` 整体被 Git 忽略，不得把 APK、签名材料或密码加入提交。

## 3. 合并代码并确定发布提交

只有已经通过完整验证的代码才能进入 `main`。提交、合并和推送必须在当前任务获得明确授权后执行。

```powershell
git switch main
git merge --ff-only <已验证分支>
.\scripts\prepare-rolling-release.ps1 `
  -ReleaseVersion 0.3.4 `
  -GradleUserHome F:\Development\gradle
```

记录此时的 `main` 提交 ID。线上 Release 编辑和 `release` Git 标签移动是两个独立动作：编辑标题、正文或附件不会自动改变标签指向。

## 4. 安全移动固定标签

移动已公开标签会重写远端引用，必须在代码已合并、发布物已复验且用户明确确认后执行。先记录远端旧值，再使用带预期旧值的租约保护更新：

```powershell
$remoteReleaseTag = (git ls-remote origin refs/tags/release).Split()[0]
git tag -f release <已验证的-main-提交>
git push `
  --force-with-lease="refs/tags/release:$remoteReleaseTag" `
  origin `
  refs/tags/release:refs/tags/release
```

如果远端标签已被其他人改动，租约检查会拒绝推送；应重新核对，不能改用无保护的强制推送。`release` 标签推送会触发 `.github/workflows/release-contract.yml`，工作流只读取代码并校验版本与发布说明，不接触签名材料。

## 5. 编辑现有 GitHub Release

不能用同一个 `release` 标签创建第二个 GitHub Release。打开现有 Release，选择 **Edit release**，按以下顺序操作：

1. 把标题改为 `LumaSR v0.3.4`，不要只写版本号，也不要追加日期或 `hotfix` 等文字。
2. 把 `docs/releases/v0.3.4.md` 的完整内容粘贴到 Release notes。
3. 删除旧 `app-release.apk`、旧版 APK、旧 `.sha256` 和旧 `release-manifest.json`。
4. 上传以下三个本地文件：

   ```text
   artifacts/releases/release/LumaSR-v0.3.4.apk
   artifacts/releases/release/LumaSR-v0.3.4.apk.sha256
   artifacts/releases/release/release-manifest.json
   ```

5. 选择 **Latest**，不要选择 **Pre-release**，然后点击 **Update release**。

删除旧附件属于不可恢复的线上写入。操作前保留本地发布目录；如果新附件上传失败，可以从该目录重新上传。更新期间短暂没有 APK 时，客户端会安全地把本次检查视为失败，不会下载旧地址拼接出的未知文件。

## 6. 发布后回查

确认 API 中标题正确且只有一个 APK：

```powershell
$release = Invoke-RestMethod `
  -Headers @{ Accept = 'application/vnd.github+json' } `
  -Uri 'https://api.github.com/repos/HF-CYGG/LumaSR/releases/latest'
$release.tag_name
$release.name
$release.assets | Select-Object name, size, browser_download_url, digest
```

v0.3.4 的原始与国内加速下载地址应分别为：

```text
https://github.com/HF-CYGG/LumaSR/releases/download/release/LumaSR-v0.3.4.apk
https://gh.jasonzeng.dev/https://github.com/HF-CYGG/LumaSR/releases/download/release/LumaSR-v0.3.4.apk
```

还需在已安装旧版的 Android 设备上验证：

- 自动或手动检查显示 `0.3.4`；
- 两个下载入口均能交给系统浏览器；
- APK 可覆盖安装且保留数据；
- 安装后的版本名为 `0.3.4`。

没有真实设备证据时，不得声称覆盖安装、厂商兼容性或下载加速已经完成真机验证。

## 失败版本处理

不要把 Release 标题回退到更低版本，也不要用相同版本号静默替换有行为差异的 APK。发现问题后应递增 `versionCode` 和 `versionName`，例如发布 `0.3.4`，重新生成版本化资产并按本流程替换。客户端会按数值版本比较发现修复版本。
