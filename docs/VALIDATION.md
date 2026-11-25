# 测试记录和已知问题

更新于 2026-11-25。

## 实机情况

原 r12 有原设备上的水平跟随成功反馈。当前源码版移除了 RunLog、RotationDiagnostics、自动落盘日志、外部存储权限、统计面板和耗时显示，保留启动及运行错误提示。整理后没有重新安装、启动 VPN 或游戏，也没有进行进程附加测试。

目前没有跨设备、跨版本或长期稳定性测试结果，也没有合入其他相机实验。

## 构建记录

| 环境 | 结果 | 记录 |
| --- | --- | --- |
| 原包中的清理版构建 | Java、JNI、zdtun、zstd 编译，DEX/APK 打包和开发签名验证通过；未实机运行 | [BUILD_VALIDATION.json](../BUILD_VALIDATION.json) |
| Windows x86_64，含中文目录 | 编译、打包、验签通过 | [WINDOWS_BUILD.json](../validation/WINDOWS_BUILD.json) |
| Ubuntu 24.04，GitHub Actions | 首次发布构建通过；后续结果查看对应提交 | [Actions](https://github.com/cos30lll/udp-aim/actions) |

Windows 环境为 Python 3.13、JDK 17.0.19、SDK 35、Build Tools 35.0.0、NDK 26.3.11579264，执行命令为 `python -B tools/build_android.py vpn --jobs 2`。

包名 `local.udp.esp.vpn`，versionCode 13，versionName `0.12.1-horizontal-clean`，arm64-v8a，minSdk 26。APK 完整性和签名验证通过，包含一个 `classes.dex` 和一个 AArch64 的 `libudpespvpn.so`，动态库依赖及内置许可证检查通过。

两次产物使用不同的开发签名，哈希分别为：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| 原包记录 | 328671 | `3b0a6c4d8cc495c94f5dcfec3bc7b199db925d6eb96cda2843b6c1d3c8bd840f` |
| Windows 重建 | 332767 | `c94bfaaa1a74d38cda49a4360e1ef35e683cdeef8714ea204be2c16e31e23fac` |

WSL2、Linux ARM64 的回退编译分支和可选 .NET 工具尚未测试。构建通过只说明能生成和签名 APK，设备效果仍需单独测试。

## 源码记录

首次导入时，`vpn/` 和 `tools/il/` 均与提供的源码包逐字节一致，见[导入记录](../validation/SOURCE_IMPORT.json)。[SOURCE_BASELINE.json](../SOURCE_BASELINE.json) 还保留了原包作者关于八个核心文件与 r12 一致的说明；由于没有 r12 参考哈希，这一条未重新核验。

`tools/check_release.py` 检查源码清单、必需文件、文档链接、Python/JSON/XML 语法及禁止提交的文件类型。初次提交前还检查了待提交文件和常见密钥标记，检查不覆盖所有安全问题。

## 已知问题

- 最近玩家按三维距离选取，跟随点为实体原点，没有头部、敌我、存活或遮挡筛选。
- ESP 使用固定相机参数和站姿尺寸，动画、镜头碰撞等情况可能导致位置偏差。
- HP 只有最近报告值，尚未取得最大血量，无法计算可靠百分比。
- 协议和相机参数针对固定版本，更新游戏后需要重新适配。
