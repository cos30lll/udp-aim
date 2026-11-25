# 维护说明

仓库：[cos30lll/udp-aim](https://github.com/cos30lll/udp-aim)。

## 更新源码

修改后先检查差异，再更新源码清单：

```sh
python3 -B tools/check_release.py --write-manifest
python3 -B tools/check_release.py
git diff --check
```

`SOURCE_MANIFEST.json` 保存当前源文件的 SHA-256，不包含自身、`.git/`、`build/` 和 `dist/`。先确认文件改动符合预期，再生成新清单。涉及代码或构建工具的修改，按[构建说明](BUILD.md)重新构建并保留报告。

推送后查看当前提交的 Actions 结果。`main` 推送、Pull Request 和手动触发都会运行源码检查和 Android 构建。

## 发布文件

仓库保留源码、文档、许可证和测试记录。游戏二进制、私人抓包、会话身份、运行日志、令牌、SDK 配置和签名私钥不提交。新增第三方依赖时，保留原版权声明，并更新 `THIRD_PARTY.md` 和依赖来源。

CI 的 APK 使用临时生成的开发签名。正式发 Release 时，需要单独确定版本号和签名，注明对应提交、文件哈希及实机测试情况。

测试结果写在 [VALIDATION.md](VALIDATION.md)。原 r12 的设备反馈、当前源码版的构建结果和后续实测分别记录；原包的 `SOURCE_BASELINE.json` 没有 r12 参考哈希，不能用来重新证明文件一致。
