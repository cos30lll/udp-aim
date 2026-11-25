# UDP Aim

Android 上基于本地 VPN 的 UDP 解码、ESP 与最近玩家水平跟随。转发指定应用的流量，在本机维护实体状态，显示姓名、距离、方框、底部射线和最近报告的 HP；水平跟随默认关闭，通过悬浮按钮切换。无需 Root、远程 VPN 服务或独立后端，目标应用包名为 `com.pi.czrxdfirst`。

原 r12 构建已获当时设备上“水平跟随可用”的反馈。本仓库是移除落盘日志和诊断面板的发布清理版，已完成 Windows 原生构建、打包及签名验证，尚未进行清理版实机验证，范围见[验证记录](docs/VALIDATION.md)。协议和近似相机配置针对固定版本；目标按三维距离选择，跟随点为实体原点，没有敌我、存活或遮挡筛选，HP 也没有可靠的最大值。

## 怎么用

本仓库发布源码，先按[构建与签名](docs/BUILD.md)准备环境。需要 Python 3.10+、JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和 NDK 26.3.11579264；支持 Linux 与 Windows 原生构建。

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py vpn --jobs 2
```

Windows 使用 `python` 代替 `python3`。输出为 `dist/udp-aim.apk`，默认使用本机新生成的开发签名，原版签名私钥不随源码提供。

手机需 Android 8+、arm64-v8a。安装后授予悬浮窗权限，点击“启动 VPN + ESP”并确认系统授权，再进入或重连目标应用。需要水平跟随时点击悬浮按钮，拖动按钮可调整位置；结束后点击“停止 VPN + ESP”。本地 VPN 与其他 VPN、加速器可能冲突。

解码与 ESP 使用原始流量副本，跟随改写不回灌状态解析器。最近目标来自同一连接中身份已确认的其他玩家；协议未知、状态过期或身份不唯一时不会强行选择。游戏更新后需要重新核对协议与相机参数。

## 文档

- [从零实现：从流量转发、身份与场景恢复到水平跟随](docs/FROM_ZERO.md)
- [构建、签名与输出](docs/BUILD.md) · [协议字段](docs/PROTOCOL.md)
- [模块与线程结构](docs/ARCHITECTURE.md) · [验证记录与限制](docs/VALIDATION.md)
- [源码发布与维护](docs/PUBLISH.md) · [第三方来源与许可证](THIRD_PARTY.md)
- [上一项目 UDP ESP](https://github.com/cos30lll/udp-esp)

Android Java 源码位于 `vpn/java/`，JNI 与第三方源码位于 `vpn/native/`；`tools/il/` 保留离线程序集检查工具源码。仓库不包含游戏二进制、私人抓包、身份日志或签名私钥。

[GPL-3.0-or-later 许可证](LICENSE) · [版权与来源声明](NOTICE)
