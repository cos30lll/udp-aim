# 验证记录与限制

日期：2026-11-25。设备反馈、原包报告与本次发布检查分别记录。

## 原包携带的记录

- 原 r12 在当时设备上获得“水平跟随可用”的反馈，只适用于原构建和该次环境。
- 根目录 [BUILD_VALIDATION.json](../BUILD_VALIDATION.json) 是原包携带的清理版构建报告：Java、JNI、zdtun、zstd 编译、DEX/APK 打包和开发签名验证通过；没有安装、启动 VPN 或游戏。
- [SOURCE_BASELINE.json](../SOURCE_BASELINE.json) 记录原作者称八个核心文件与 r12 一致，但未附 r12 参考哈希，本次无法独立验证这一历史结论。
- 清理范围包含移除 RunLog、RotationDiagnostics、日志落盘、外部存储权限、统计面板及耗时显示，保留启动和运行错误提示。

历史 APK 指纹为 `3b0a6c4d8cc495c94f5dcfec3bc7b199db925d6eb96cda2843b6c1d3c8bd840f`，328671 字节。它不对应本次重新签名的产物。

## 本次 Windows 发布检查

- Windows x86_64、Python 3.13、JDK 17.0.19、SDK Platform 35、Build Tools 35.0.0、NDK 26.3.11579264。
- 在含中文路径的仓库目录运行 `python -B tools/build_android.py vpn --jobs 2`，Java、JNI 和第三方源码构建、APK 打包及签名验证通过。
- 包名 `local.udp.esp.vpn`，versionCode 13，versionName `0.12.1-horizontal-clean`，arm64-v8a，minSdk 26。
- APK 归档完整性通过，仅含一个 `classes.dex` 和 `lib/arm64-v8a/libudpespvpn.so`；原生 ELF 为 AArch64，依赖检查通过，许可证文本已嵌入 APK。
- [Windows 构建报告](../validation/WINDOWS_BUILD.json) 保留当前产物与源文件指纹。本机开发签名与原包签名不同。
- [源码导入对照](../validation/SOURCE_IMPORT.json) 记录相对于用户提供压缩包的修改范围；所有 `vpn/` 与 `tools/il/` 文件按字节保持一致。这项对照不等同于与原 r12 构建进行比对。
- `tools/check_release.py` 检查文件清单、必需文件、本地文档链接、Python/JSON/XML 语法和被排除的生成文件类型；提交前另核对待提交文件及常见密钥标记。工具不提供完整安全认证。

本次 Windows APK SHA-256：`c94bfaaa1a74d38cda49a4360e1ef35e683cdeef8714ea204be2c16e31e23fac`，332767 字节。构建结果不要求与其他签名或构建主机的 APK 字节一致。

## CI 与其他环境

[GitHub Actions](https://github.com/cos30lll/udp-aim/actions) 在 `main` 推送、Pull Request 和手动触发时运行源码检查与 Ubuntu 24.04 Android 构建；应以对应提交的检查结果为准。本机记录不替代云端结果。

WSL2、Linux ARM64 回退编译分支及可选 .NET 工程未在本次发布中运行。

## 运行限制

发布清理版未在本次工作中安装或启动，未启动 VPN、游戏或附加进程。没有新的设备效果、跨版本兼容或长期稳定性结论；未合入其他相机实验。

最近目标按三维距离选取同一连接中已确认身份的其他玩家，跟随点为实体原点，不包含头部、敌我、存活或遮挡筛选。ESP 使用固定相机参数及站姿尺寸，实际动画和镜头碰撞可能产生偏差；HP 为最近报告的数值，没有最大值，不能计算可靠百分比。
