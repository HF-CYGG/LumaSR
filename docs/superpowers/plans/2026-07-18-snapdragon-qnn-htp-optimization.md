# Snapdragon QNN/HTP 深度优化执行计划

> 直接在当前 `codex/performance-next` 实施；不新建分支或 worktree，不使用子 agent。

1. 记录基线、外置 SDK/语料契约、许可与资产锁格式。
2. 以 TDD 增加 QNN 领域类型、schema 兼容、AUTO/显式 HTP 选择和 15% 持续性能门。
3. 以 TDD 增加可替换运行时函数表、能力探测、context 身份/回收和失败回退。
4. 增加 QAIRT 构建开关、生成型 jniLibs/assets 输入和固定签名 APK 内容校验。
5. 扩展手动校准步骤、ETA、完整日志、FP16/A16W8 质量与持续性能记录。
6. 实现 QNN depth 1 正确性路径，再实现有界共享缓冲 depth 2、热控制和取消。
7. 为 ncnn 回退增加逐像素一致的 AArch64 NEON 转换，为 API 31+ 增加 ADPF。
8. 在外部条件满足后生成首批 DLC，执行真机自检、质量比较和 30 轮持续基准。
9. 运行 JVM、Lint、Native、Debug/Benchmark/Release 和固定签名验证；每阶段使用
   中文提交，停留在当前分支，不合并或推送。

实现期间每个行为先写失败测试并确认因功能缺失而失败，再写最小实现；每次代码
变更后运行定向测试和 `git diff --check`。真实 QAIRT、量化与设备阶段缺少任一外部
输入时必须报告阻塞，不用占位数据伪造完成。
