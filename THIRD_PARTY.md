# 第三方依赖

| 组件 | 本包位置 | 许可 |
| --- | --- | --- |
| zdtun | `vpn/native/third_party/zdtun/` | LGPL-3.0-or-later，见 COPYING |
| Zstandard 1.5.7 | `vpn/native/third_party/zstd/` | 使用 BSD-3-Clause 选项，见 LICENSE；保留 COPYING |
| uthash 及其他内嵌头文件 | zdtun 源码目录 | 见各源文件中的声明 |

项目自身沿用现有 GPL-3.0-or-later 许可，全文见根目录 LICENSE。第三方源码保留原始声明。下载来源与原归档 SHA-256 位于 `vpn/dependency-sources.json`；归档哈希不等同于目录哈希。

`tools/il` 使用外部提供的 Mono.Cecil。该依赖的二进制和游戏程序集均不在包内。Android 应用使用系统 org.json，不需要另打包 JSON JAR。
