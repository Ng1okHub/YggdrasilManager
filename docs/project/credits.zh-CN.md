# 第三方组件

[English](credits.en.md) | [繁體中文](credits.zh-TW.md) | **简体中文**

以下组件支持 Manager 的原生调用栈回溯与 Root 操作。本页说明其用途，许可条款仍以原文为准。

## libunwindstack-ndk

原生调用栈回溯使用项目内置的 `libunwindstack-ndk`，这是经过修补、可用 Android NDK 构建的 libunwindstack 版本。上游说明指出，它源自 [ivanarh 的 libunwindstack-ndk](https://github.com/ivanarh/libunwindstack-ndk)，并持续跟踪 [Android 的 system/unwinding](https://android.googlesource.com/platform/system/unwinding)。

上游移除了无法在 Android 构建或不再适用的部分，包括 MIPS 支持，以及用于符号解析的 GNU 调试信息。

内置源码保留[上游原始说明](../../app/src/main/cpp/third_party/libunwindstack-ndk/README.md)与[许可声明](../../app/src/main/cpp/third_party/libunwindstack-ndk/LICENSE)。许可证文件包含 Apache 与 Unicode 声明，本页不翻译许可原文。

## libsu

Root 操作使用 [libsu](https://github.com/topjohnwu/libsu)。Android App 在 Gradle 版本目录中声明其 `core` 与 `service` 依赖。

## 项目许可

项目仍需选择整体许可证。依赖许可证审查，以及如何随 APK 分发声明，仍列于[开源待定事项](../release/open-source-readiness.zh-CN.md)。
