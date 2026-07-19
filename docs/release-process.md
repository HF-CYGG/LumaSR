# LumaSR 正式发布流程

本文档定义与应用内 GitHub 更新检查兼容的正式发布契约。发布构建在本机完成，GitHub Actions 只检查标签和版本元数据，不接触签名私钥。

## 不可变发布契约

- `app/build.gradle.kts` 中的 `versionCode` 每次发布必须严格递增。
- `versionName` 使用 2–4 段纯数字，例如 `0.3.2`、`1.0.5.1`。
- Git 标签是 `v` 加 `versionName`，例如 `v0.3.2`。
- 每个正式版本创建新的不可变标签；不得继续复用历史 `release` 标签，也不得覆盖已发布标签或附件。
- GitHub Release 必须是正式、非草稿、非预发布版本，并设为 latest。
- 唯一 APK 附件固定命名为 `LumaSR.apk`；摘要文件为 `LumaSR.apk.sha256`。
- APK 必须使用固定证书签名，证书 SHA-256 为 `43648398DD74C72B1CBA185D3832E558C72D9BB58AE46BB9C09EF32818988D2F`。

客户端通过 GitHub `/releases/latest` 获取标签及 `assets[].browser_download_url`。规范命名可以避免多个 APK 造成歧义，国内地址由客户端在校验原始 GitHub URL 后添加 `https://gh.jasonzeng.dev/` 前缀。

## 1. 准备版本和说明

1. 将 `versionCode` 加一，并设置新的数字 `versionName`。
2. 新建 `docs/releases/v版本.md`，例如 `docs/releases/v0.3.2.md`。
3. 运行元数据契约测试：

```powershell
.\scripts\validate-release-metadata.ps1 -TagName v0.3.2
.\scripts\test-release-workflow.ps1
```

## 2. 构建固定签名 Release

私钥和签名环境继续保存在被 Git 忽略的 `artifacts/signing` 中。不得复制、提交、打印或轮换现有密钥。

```powershell
.\scripts\build-fixed-release.ps1 -GradleUserHome F:\Development\gradle
```

该命令依次运行 JVM 测试、Android Lint、Release 构建、固定证书校验、APK 清单版本校验和 ZIP 对齐校验，然后生成：

```text
artifacts/releases/v0.3.2/LumaSR.apk
artifacts/releases/v0.3.2/LumaSR.apk.sha256
artifacts/releases/v0.3.2/release-manifest.json
```

`artifacts` 整体被 Git 忽略，不得把 APK、私钥或密码加入提交。

## 3. 合并、复验和打标签

在功能分支验证通过后提交并本地合并：

```powershell
git switch main
git merge --ff-only <已验证分支>
.\scripts\build-fixed-release.ps1 -GradleUserHome F:\Development\gradle
git tag -a v0.3.2 -m "LumaSR v0.3.2"
```

标签必须指向已经复验的 `main` HEAD。标签创建后先执行只读发布预检：

```powershell
.\scripts\publish-github-release.ps1 -TagName v0.3.2
```

## 4. 推送和发布 GitHub Release

推送属于外部写入，应在明确确认后执行：

```powershell
git push origin main
git push origin v0.3.2
```

标签推送会触发 `.github/workflows/release-contract.yml`，它只校验标签、应用版本和发布说明，不读取签名材料。门禁通过后，使用已认证的 GitHub CLI 发布：

```powershell
.\scripts\publish-github-release.ps1 -TagName v0.3.2 -Publish
```

脚本拒绝脏工作区、非 `main` 分支、未指向 HEAD 的标签、摘要不一致、证书错误以及已存在的 Release，因此不会覆盖不可变版本。

## 5. 发布后回查

确认 GitHub API 返回新标签和规范 APK：

```powershell
curl.exe -L https://api.github.com/repos/HF-CYGG/LumaSR/releases/latest
curl.exe -I -L https://gh.jasonzeng.dev/https://github.com/HF-CYGG/LumaSR/releases/download/v0.3.2/LumaSR.apk
```

还需在已安装旧版的 Android 设备上确认应用提示 `v0.3.2`，两个下载入口均能打开，并且固定签名 APK 可以覆盖安装且保留数据。

## 失败版本处理

已经公开的标签和 APK 不得原地替换。若发现问题，停止推荐有问题的版本并发布递增的补丁版本，例如 `v0.3.3`；客户端会按数字版本比较自动发现修复版本。
