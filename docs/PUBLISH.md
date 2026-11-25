# 源码发布与维护

公开仓库：[cos30lll/udp-aim](https://github.com/cos30lll/udp-aim)。首页沿用上一项目 UDP ESP 的“简介 → 怎么用 → 文档”结构，详细实现保留在 `docs/`。

## 提交前检查

1. 核对 README、实现文档与验证记录，区分原 r12 的反馈、历史构建报告和本次执行的检查。
2. 审阅改动后重新生成源码清单，再验证清单：

   ```sh
   python3 -B tools/check_release.py --write-manifest
   python3 -B tools/check_release.py
   git diff --check
   ```

3. 按[构建说明](BUILD.md)完成相关构建，保留产物报告。检查待提交文件与第三方版权声明。
4. 提交并推送源码，检查当前提交的 GitHub Actions 结果。`main` 推送、Pull Request 和手动触发均运行源码检查及 Android 构建。

`SOURCE_MANIFEST.json` 记录当前源文件 SHA-256；它不包含自己、`.git/`、`build/`、`dist/` 等生成目录。重新生成清单前必须先审阅差异。`SOURCE_BASELINE.json` 是原包作者留下的 r12 来源与清理范围记录，没有提供 r12 参考哈希；不要把它写成本次独立验证的结论。

## 发布范围

公开 Git 历史只保留源码、许可证与必要验证报告，不加入游戏二进制、私人抓包、会话身份、运行日志、访问令牌、本机 SDK 配置和签名私钥。新增第三方内容时保留原版权及许可，并更新 `THIRD_PARTY.md` 和依赖来源记录。

GitHub Actions 产物为本次生成的开发签名 APK 及构建报告。工作流成功表示编译、打包和签名验证通过，不表示已经安装或完成游戏实测。每次发布都应同步[验证记录](VALIDATION.md)的执行范围，不将旧设备反馈扩展为跨版本或跨设备结论。

源码仓库不直接提交 APK。若另行制作正式 Release，应核对版本、对应源码提交、许可和签名策略，并单独说明实机验证结果。
