<div align="left">
    <picture>
        <img alt="YggdrasilManager 樹形圖示" src="docs/brand/yggdrasil-icon.png" width="96">
    </picture>

<h1>YggdrasilManager</h1>

![CI](https://github.com/ngiokweng/YggdrasilManager/workflows/CI/badge.svg)

</div>

[English](README.md) | **繁體中文** | [简体中文](README.zh-CN.md)

Yggdrasil KPM 模組的 Android 管理工具，使用 Compose 介面，並提供硬體斷點與 arm64 系統呼叫觀察的瀏覽器控制台。也能管理偵錯狀態與記憶體映射政策，內附可獨立執行的模組自測程式。

## 使用需求

- 執行 Android 7.0／API 24 或更新版本的 arm64 Android 裝置。
- Root 權限，以及已載入的相容 Yggdrasil KPM 模組。Manager 不負責安裝核心模組。
- 建置環境：JDK 21、Node.js 22 或更新版本，以及 Android SDK。SDK、NDK 與 CMake 的確切版本請見[建置指南](docs/release/releasing.zh-TW.md#toolchain)。

## 安裝

從原始碼建置 debug APK：

```sh
git clone https://github.com/ngiokweng/YggdrasilManager.git
cd YggdrasilManager
npm ci --prefix frontend
npm run sync:android --prefix frontend
bash ./gradlew :app:assembleDebug
```

`sync:android` 會以重新建置的前端檔案取代 App 內附的瀏覽器資源。在 Windows PowerShell 中，請將最後一行改為 `.\gradlew.bat :app:assembleDebug`。

使用 Android 平台工具安裝 APK：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 版的套件名稱為 `ng1ok.yggdrasilmanager.debug`，可與套件名稱為 `ng1ok.yggdrasilmanager` 的簽名 release 版並存。正式 APK 的建置方式請見 [release 建置與簽名](docs/release/releasing.zh-TW.md)。

## 設定

使用裝置上的模組工具載入 Yggdrasil 模組，再開啟 Manager 並授予 Root 權限。首頁會分別顯示模組狀態、Root 狀態與 Manager 版本。

瀏覽器控制台沒有身分驗證。請只在可信任的網路使用，並於使用完畢後停止伺服器。

## 快速開始

1. 確認首頁顯示模組可用，且已取得 Root 權限。
2. 開啟 **功能 → Arm64 Syscall Observe** 或 **HWBP Observe**。
3. 在同一網路的瀏覽器中開啟手機顯示的控制台網址。HWBP 使用連接埠 `8765`，Syscall 使用 `8766`。

[圖文使用指南](docs/user-guide/guide.zh-TW.md)提供四項功能的操作步驟與畫面說明。

## 功能

- **Arm64 Syscall Observe**：攔截與改寫系統呼叫、擷取引數，並在瀏覽器檢視事件。
- **HWBP Observe**：設定硬體斷點，檢視命中事件與呼叫堆疊。
- **Hide DBG**：依應用程式管理偵錯器與停止狀態政策。
- **Hide Maps**：隱藏記憶體映射項目，或改寫顯示的存取權限。
- **模組自測**：透過 Root 執行內附的 arm64 程式，並檢視各測試區段的結果。

實際可用的操作取決於已載入的模組與裝置。

## 文件

- [圖文使用指南](docs/user-guide/guide.zh-TW.md)
- [建置、簽名、版本管理與 GitHub 發佈](docs/release/releasing.zh-TW.md)
- [開源準備與待決事項](docs/release/open-source-readiness.zh-TW.md)
- [瀏覽器控制台開發](docs/development/frontend.zh-TW.md)
- [更新紀錄](docs/release/changelog.zh-TW.md)

## 測試

```sh
node --test scripts/test/*.test.mjs
npm test --prefix frontend
npm audit --prefix frontend --audit-level=high
bash ./gradlew :app:testDebugUnitTest :app:lintDebug
```

CI 也會重新建置瀏覽器資源，並編譯 debug 與 release APK。CI 的 release 建置使用臨時金鑰；版本標籤觸發的發佈流程則使用正式簽名私鑰，並在發佈前核對產物的憑證。

## 致謝

原生呼叫堆疊回溯使用專案內附的 `libunwindstack-ndk`，Root 操作使用 `libsu`。元件來源與保留的授權聲明請見[第三方元件](docs/project/credits.zh-TW.md)。

專案尚需選定整體授權條款，才能以開源專案的名義對外提供；請見[待決事項](docs/release/open-source-readiness.zh-TW.md)。
