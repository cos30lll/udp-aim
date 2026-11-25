# 构建与签名

使用 `tools/build_android.py` 构建，不需要 Gradle。目标为 arm64-v8a、minSdk 26、targetSdk 35；版本代码 13，版本名称 `0.12.1-horizontal-clean`。

## 环境

准备 Python 3.10+、JDK 17，以及 Android SDK 的以下组件：

```text
platforms;android-35
build-tools;35.0.0
ndk;26.3.11579264
```

`java`、`javac` 和 `keytool` 需位于 PATH。SDK 通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定；脚本也检查 `~/Android/Sdk`，Windows 另检查 `%LOCALAPPDATA%/Android/Sdk`。建议明确指定 NDK 版本。

## Linux

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
python3 -B tools/check_release.py
python3 -B tools/build_android.py vpn --jobs 2
```

GitHub Actions 使用 Ubuntu 24.04、JDK 17 与上述 SDK/NDK 组件。Linux ARM64 主机若不能运行 NDK 自带的主机编译器，可提供本机 `clang-18`/`lld-18`；该分支和 WSL2 尚未测试。

## Windows PowerShell

使用 Windows 版 JDK、SDK 和 NDK，在仓库根目录运行：

```powershell
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_SDK_ROOT\ndk\26.3.11579264"
python -B tools/check_release.py
python -B tools/build_android.py vpn --jobs 2
```

Windows 构建已通过，包括含中文的工作目录。使用 WSL2 时，应在 WSL2 内安装 Linux 版工具，不混用 Windows 与 Linux 可执行文件。

## 输出

```text
build/vpn/build.log
dist/udp-aim.apk
dist/vpn-build.json
dist/udp-aim.apk.sha256
```

脚本编译 Java、JNI、zdtun 与 zstd 的 common/decompress/compress 源码，生成 DEX、APK，并验证签名。构建报告记录 APK 和源文件的哈希。`UDP_AIM_BUILD_DIR`、`UDP_AIM_DIST_DIR` 可将构建及成品放到仓库之外。构建不会安装 APK 或启动设备应用。

## 签名

首次构建默认在 `build/signing/` 生成本机开发密钥，不随源码发布。它与原 r12 签名不同，不能保证覆盖安装。

自行签名时，通过环境变量提供 `UDP_ESP_KEYSTORE`、`UDP_ESP_KEY_ALIAS`、`UDP_ESP_STOREPASS`、`UDP_ESP_KEYPASS`；这些名称保留上个工程的兼容约定。不要将私钥或真实密码写入仓库。CI 使用每次构建生成的开发签名，不作为稳定更新渠道。

## 可选离线程序集工具

`tools/il/` 含两个 .NET 8 工程及完整源码。构建时由使用者自行提供 `Mono.Cecil.dll`，通过 `CecilPath` 指定，见[工具说明](../tools/il/README.md)。两个工具只读取文件，不执行被分析的程序集；游戏程序集及第三方 DLL 不随源码发布。这两个可选工具尚未构建测试。

已测试的环境和结果见[测试记录](VALIDATION.md)。
