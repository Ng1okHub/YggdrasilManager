# Third-party components

**English** | [繁體中文](credits.zh-TW.md) | [简体中文](credits.zh-CN.md)

These components support the Manager's native backtraces and Root operations. This page describes their role; their original license texts remain authoritative.

## libunwindstack-ndk

Native backtraces use the vendored `libunwindstack-ndk`, a version of libunwindstack patched to build with the Android NDK. Its upstream description credits [ivanarh's libunwindstack-ndk](https://github.com/ivanarh/libunwindstack-ndk) and tracks [Android's system/unwinding](https://android.googlesource.com/platform/system/unwinding).

The upstream adaptation removes components that do not build on Android or are no longer useful there, including MIPS support and GNU debug information for symbolication.

The [original upstream description](../../app/src/main/cpp/third_party/libunwindstack-ndk/README.md) and [license notices](../../app/src/main/cpp/third_party/libunwindstack-ndk/LICENSE) are preserved in the vendored source. The license file includes Apache and Unicode notices; it is not translated here.

## libsu

Root operations use [libsu](https://github.com/topjohnwu/libsu). The Android app declares its `core` and `service` dependencies in the Gradle version catalog.

## Project licensing

A project-wide license still needs to be selected. Dependency-license auditing and the way notices will be distributed with the APK remain among the [open-source decisions](../release/open-source-readiness.en.md).
