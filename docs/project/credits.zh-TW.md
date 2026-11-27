# 第三方元件

[English](credits.en.md) | **繁體中文** | [简体中文](credits.zh-CN.md)

以下元件支援 Manager 的原生呼叫堆疊回溯與 Root 操作。本頁說明其用途，授權條款仍以原文為準。

## libunwindstack-ndk

原生呼叫堆疊回溯使用專案內附的 `libunwindstack-ndk`，這是經過修補、可用 Android NDK 建置的 libunwindstack 版本。上游說明指出，它源自 [ivanarh 的 libunwindstack-ndk](https://github.com/ivanarh/libunwindstack-ndk)，並持續追蹤 [Android 的 system/unwinding](https://android.googlesource.com/platform/system/unwinding)。

上游移除了無法在 Android 建置或不再適用的部分，包括 MIPS 支援，以及用於符號解析的 GNU 偵錯資訊。

內附原始碼保留[上游原始說明](../../app/src/main/cpp/third_party/libunwindstack-ndk/README.md)與[授權聲明](../../app/src/main/cpp/third_party/libunwindstack-ndk/LICENSE)。授權檔案包含 Apache 與 Unicode 聲明，本頁不翻譯授權原文。

## libsu

Root 操作使用 [libsu](https://github.com/topjohnwu/libsu)。Android App 在 Gradle 版本目錄中宣告其 `core` 與 `service` 相依套件。

## 專案授權

專案仍需選擇整體授權條款。相依套件授權稽核，以及如何隨 APK 分發聲明，仍列於[開源待決事項](../release/open-source-readiness.zh-TW.md)。
