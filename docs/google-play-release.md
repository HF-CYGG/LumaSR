# LumaSR Google Play 发布流程

本文档只覆盖本地签名、构建与验证。Play Console 中的软件包名称固定使用 `com.lumasr`；上传、Play App Signing 配置和政策表单仍由发布者在控制台完成。

## 1. 一次性初始化上传密钥

在仓库根目录执行：

```powershell
.\scripts\initialize-google-play-upload-key.ps1
```

脚本生成独立 RSA 4096 上传密钥、公开证书和被 Git 忽略的签名环境文件：

```text
artifacts/signing/lumasr-play-upload.jks
artifacts/signing/lumasr-play-upload-cert.pem
artifacts/signing/lumasr-play-upload-signing.env
```

脚本拒绝覆盖任何已有文件。必须把 JKS 与环境文件分别备份到受控的加密存储；不得提交、上传或打印其中的私钥和密码。

## 2. 构建发布物

```powershell
.\scripts\build-google-play-release.ps1 -GradleUserHome F:\Development\gradle
```

脚本执行 JVM 测试、Android Lint、Debug/Release/Play 构建，并验证 AAB 与 APK 签名、包名、版本、targetSdk、bundletool 16 KB 配置、ZIP 对齐、全部原生库的 ELF LOAD 对齐及 GNU RELRO。输出目录为：

```text
artifacts/releases/google-play/v0.3.4/
```

其中 `LumaSR-0.3.4-google-play.aab` 用于 Play Console，`LumaSR-v0.3.4.apk` 只用于本地或 GitHub 安装。`release-manifest.json` 记录签名指纹、页对齐结论、文件大小和 bundletool 下载大小估算；`checksums.sha256` 用于交付校验。

## 3. 配置 Play App Signing

首次配置时选择“提供现有应用签名密钥”。现有固定签名密钥作为应用签名密钥，以保持 Google Play 与现有 GitHub APK 的覆盖安装兼容性；新生成的 `lumasr-play-upload.jks` 只作为上传密钥。

PEPK 加密命令、加密公钥和输出格式必须使用当前 Play Console 页面提供的内容。只向控制台提交 PEPK 生成的加密材料，不复制或输出原始应用签名私钥、密码。注册上传密钥时使用发布目录中的 `play-upload-certificate.pem`，应用签名公钥证书为 `app-signing-certificate.pem`。

## 4. 上传前检查

- 在 Play Console 创建应用时填写软件包名称 `com.lumasr` 并执行“检查可用性”；若已占用，停止发布并确认新的永久包名。
- 上传 `LumaSR-0.3.4-google-play.aab`，不要上传 APK。
- 同时保留 `mapping.txt`、`native-debug-symbols.zip`、`checksums.sha256` 和 `release-manifest.json`。
- 在真实 16 KB 页设备或 AVD 上完成启动、图片选择和处理冒烟后，再宣称运行时 16 KB 兼容。
