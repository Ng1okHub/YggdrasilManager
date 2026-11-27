# Changelog

**English** | [繁體中文](changelog.zh-TW.md) | [简体中文](changelog.zh-CN.md)

Manager versions follow Semantic Versioning and are independent of the Yggdrasil KPM module version. Android update ordering uses the separate, increasing `versionCode` in `version.properties`.

## [1.0.0] - 2026-11-27

- Manage arm64 syscall observation, hardware breakpoints, Hide DBG and Hide Maps.
- Provide browser consoles for syscall and HWBP events and backtraces.
- Bundle the standalone arm64 Yggdrasil module self-test.
- Add signed release builds, centralized Manager versioning and GitHub Actions verification and release workflows.
