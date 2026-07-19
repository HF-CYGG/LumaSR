# QNN 外置包契约

LumaSR 默认不编译、不打包 QAIRT。只有同时满足以下条件时，才允许显式使用
`-Plumasr.enableQnn=true`：

1. 在仓库外用批准的 QAIRT 2.46.x 工具链生成 Adapter、Stub/Skel 和 DLC；
2. 将可分发文件放入独立目录，并设置 `LUMASR_QNN_PACKAGE_DIR`；
3. 在该目录生成 `qnn-package-lock.json`，逐文件记录小写 SHA-256；
4. `assets/qnn/graph-manifest.json` 明确记录每个图的精度、core tile、CPU 分区数和不支持算子；
5. 使用固定签名脚本构建 Release，并继续执行 APK 证书校验。

外置目录结构：

```text
qnn-package/
├── qnn-package-lock.json
├── jniLibs/arm64-v8a/
│   ├── liblumasr_qnn_adapter.so
│   ├── libQnnSystem.so
│   ├── libQnnHtp.so
│   └── ...经许可的最小 Stub/Skel 集合
└── assets/qnn/
    ├── graph-manifest.json
    └── *.bin
```

`qnn-package-lock.json` 的最小格式：

```json
{
  "schemaVersion": 1,
  "qairtVersion": "2.46.0",
  "artifacts": [
    {
      "path": "jniLibs/arm64-v8a/liblumasr_qnn_adapter.so",
      "sha256": "64 位小写十六进制 SHA-256"
    }
  ]
}
```

`graph-manifest.json` 的图记录格式：

```json
{
  "schemaVersion": 1,
  "graphs": [
    {
      "graphKey": "realcugan-x4-fp16-v73",
      "artifactPath": "assets/qnn/realcugan-x4-fp16-v73.bin",
      "precision": "FP16",
      "tileSize": 256,
      "cpuPartitions": 0,
      "unsupportedOperators": []
    }
  ]
}
```

构建门会拒绝路径越界、重复路径、未锁定图、哈希不一致、非 2.46.x 工具链、CPU
分区、不支持算子以及文件名含 `calibration` 的语料内容。SDK、头文件、转换工具、授权
语料、密钥和用户本机路径不得进入 APK、Git 或校准日志。

本仓库当前不包含真实 DLC 或 Qualcomm 专有二进制；没有外置 SDK、授权语料和真机证据时，
不得声称 HTP 已启用、已提速或已通过任何 OEM 兼容验证。
