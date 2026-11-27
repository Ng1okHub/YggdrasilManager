# 开源前的待定事项

[English](open-source-readiness.en.md) | [繁體中文](open-source-readiness.zh-TW.md) | **简体中文**

检查日期：2026-11-27。Release 构建、Manager 版本来源、本地签名工具、GitHub 验证与发布流程、README 及更新日志已就绪。公开前仍需完成以下决策与检查。

## 首次公开发布前

1. **选择项目许可证**。根目录目前没有 `LICENSE`，GitHub 也未识别出项目许可证。公开仓库本身不会授予他人复用权。请先为自己拥有的代码选择许可证，并核对内核 ABI 头文件、原生依赖、JVM 依赖及前端资源的兼容性，再添加许可证文件。目前尚未代为选定许可证。
2. **补齐第三方署名与声明**。内置的 `libunwindstack-ndk/LICENSE` 包含 Apache 与 Unicode 声明，个别原生文件也保留上游版权标头。请审查原生、Gradle 与 npm 依赖的许可证，保留必要声明，并决定如何随 APK 分发，例如“关于／许可证”页面搭配声明资源文件。
3. **决定浏览器控制台的访问方式**。两个服务器目前均监听 `0.0.0.0`（HWBP 为 `8765`、Syscall 为 `8766`），且无需身份验证即可接受配置命令。请记录可信网络的使用前提；广泛分发前，可考虑默认仅监听 localhost 并通过 ADB 转发，或添加身份验证与来源验证。这属于另一项产品决策。
4. **将发布密钥备份到工作区外**。请保留 `.signing/yggdrasilmanager-release.p12` 与 `keystore.properties` 的加密副本，并确认能重现公开证书。四项 GitHub Actions Secrets 已配置，但无法替代可恢复的备份。
5. **进行 release 真机冒烟测试**。安装 release APK，检查 Root 绑定、模块检测、四项工具、两种浏览器控制台与内置自测程序。声明支持 Android 7 时需覆盖 API 24／25；条件允许时，也应包含 16 KB 内存页设备。JVM 测试、lint、签名验证与 ZIP 对齐检查无法证明这些运行时行为。

## 仓库维护

- 推送审阅完成的变更，确认首次 GitHub Actions 运行结果。为默认分支（检查时为 `master`）设置 CI 必过与审阅规则，并保护版本标签。当前 GitHub 方案下的私有仓库无法查看 rulesets（API 返回 HTTP 403）。
- 定义贡献方式与漏洞报告渠道。目前缺少 `CONTRIBUTING.md`、`SECURITY.md`、issue 模板与 PR 模板。Issue 指引应包含支持的模块版本与最小诊断信息。
- 更改可见性前，检查完整 Git 历史中的凭据与私人数据。当前忽略规则可保护新的签名文件，但不会删除已有 commit 中的内容。本次变更尚未执行完整历史的敏感信息扫描。
- 在更新日志中记录测试过的设备／模块组合与首次发布日期。前端 lockfile 更新后，依赖 audit 已无告警；Android lint 尚有依赖更新与未使用资源等警告。

检查时仓库为私有。请完成上述公开发布检查后，再更改可见性。
