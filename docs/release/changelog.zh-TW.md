# 更新紀錄

[English](changelog.en.md) | **繁體中文** | [简体中文](changelog.zh-CN.md)

Manager 版本遵循語意化版本規範（SemVer），與 Yggdrasil KPM 模組版本分開管理。Android 更新順序由 `version.properties` 中獨立且遞增的 `versionCode` 決定。

## [1.0.0] - 2026-11-27

- 管理 arm64 系統呼叫觀察、硬體斷點、Hide DBG 與 Hide Maps。
- 提供瀏覽器控制台，檢視系統呼叫與 HWBP 事件及呼叫堆疊。
- 內附可獨立執行的 arm64 Yggdrasil 模組自測程式。
- 新增簽名 release 建置、集中式 Manager 版本管理，以及 GitHub Actions 驗證與發佈流程。
