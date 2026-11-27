# 更新日志

[English](changelog.en.md) | [繁體中文](changelog.zh-TW.md) | **简体中文**

Manager 版本遵循语义化版本规范（SemVer），与 Yggdrasil KPM 模块版本分开管理。Android 更新顺序由 `version.properties` 中独立且递增的 `versionCode` 决定。

## [1.0.0] - 2026-11-27

- 管理 arm64 系统调用观察、硬件断点、Hide DBG 与 Hide Maps。
- 提供浏览器控制台，查看系统调用与 HWBP 事件及调用栈。
- 内置可独立运行的 arm64 Yggdrasil 模块自测程序。
- 新增签名 release 构建、集中式 Manager 版本管理，以及 GitHub Actions 验证与发布流程。
