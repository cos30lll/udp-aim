# UDP Aim  [ECAPI 中转站](https://ecmod.zhake.win)的许愿编程生成

Android 上的 UDP ESP 和水平跟随工具，使用本地 VPN 转发游戏流量，不需要 Root 或独立后端。ESP 显示玩家名、距离、方框、射线和 HP；水平跟随默认关闭，点击悬浮按钮开启。

目标包名为 `com.pi.czrxdfirst`。跟随会选择同一连接中身份已确认、三维距离最近的其他玩家，朝向实体原点，不判断敌我、存活或遮挡。协议和相机参数针对固定版本，游戏更新后需要重新适配。

r12 的水平跟随已有用户在原设备确认可用。当前源码版去掉了落盘日志和诊断面板，Windows 本机构建和 Ubuntu CI 构建均已通过，尚未重新实机测试。详细情况见[测试记录](docs/VALIDATION.md)。

## 怎么用

准备 Python 3.10+、JDK 17、Android SDK 35、Build Tools 35.0.0 和 NDK 26.3.11579264，在仓库根目录运行：

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py vpn --jobs 2
```

Windows 将 `python3` 换成 `python`。安装包输出到 `dist/udp-aim.apk`，使用本机生成的开发签名；具体环境和签名配置见[构建说明](docs/BUILD.md)。

手机需要 Android 8+、arm64-v8a。安装后授予悬浮窗权限，点击“启动 VPN + ESP”，确认系统授权，再进入或重连游戏。悬浮按钮可以切换水平跟随，也可以拖动位置。结束后点击“停止 VPN + ESP”。与其他 VPN 或加速器可能冲突。

## 文档

- [从零实现](docs/FROM_ZERO.md) · [协议字段](docs/PROTOCOL.md)
- [构建与签名](docs/BUILD.md) · [代码结构](docs/ARCHITECTURE.md)
- [测试记录和已知问题](docs/VALIDATION.md) · [维护说明](docs/PUBLISH.md)
- [第三方依赖](THIRD_PARTY.md) · [UDP ESP](https://github.com/cos30lll/udp-esp)

Java 源码在 `vpn/java/`，JNI 和第三方源码在 `vpn/native/`，离线程序集工具在 `tools/il/`。

[GPL-3.0-or-later](LICENSE) · [版权声明](NOTICE)
