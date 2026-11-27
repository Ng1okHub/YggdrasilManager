# 建置與發佈 YggdrasilManager

[English](releasing.en.md) | **繁體中文** | [简体中文](releasing.zh-CN.md)

Manager 版本來自根目錄的 `version.properties`。Android 首頁讀取 `BuildConfig.VERSION_NAME`，因此介面與 APK 使用相同的版本資訊。核心模組有獨立的版本與發佈週期。

<a id="toolchain"></a>

## 工具鏈

| 元件 | 版本 |
| --- | --- |
| JDK | 21 |
| Node.js | 22 或更新版本；GitHub Actions 使用 22 |
| Gradle wrapper | 9.4.1，驗證發行檔的 SHA-256 |
| Android Gradle plugin | 9.2.1 |
| Android compile SDK | 36.1（`platforms;android-36.1`） |
| Android build tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

APK 僅包含 `arm64-v8a`；KPM ABI、系統呼叫目錄與內附自測程式均以 arm64 為目標。Android 最低版本為 API 24，目標版本為 API 36。

透過 Android Studio 或 `sdkmanager` 安裝 Android 套件：

```sh
sdkmanager 'platforms;android-36.1' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
```

將 `ANDROID_HOME` 指向 SDK，或在已排除於 Git 的 `local.properties` 設定 `sdk.dir`。Windows PowerShell 請以 `.\gradlew.bat` 取代 `bash ./gradlew`。指令明確使用 `bash`，是因為儲存庫中的 wrapper 目前未設定 Unix 可執行權限。

## 版本管理

```sh
node scripts/version.mjs show
node scripts/version.mjs bump patch
```

`bump major`、`bump minor` 與 `bump patch` 會增加對應的 SemVer 版本欄位、清除預發佈後綴，並將 Android `versionCode` 加一。每次發佈選擇一種升版方式。

建立預發佈版本，或將預發佈版本升為正式版時，使用 `set`：

```sh
node scripts/version.mjs set 1.1.0-rc.1
node scripts/version.mjs set 1.1.0
```

每次 `set` 也會將 `versionCode` 加一。相同或更低的 SemVer 版本會被拒絕。版本名稱不包含建置中繼資料（`+...`），讓每個可接受的名稱對應唯一的發佈標籤。版本碼必須介於 1 至 2,100,000,000，且高於所有已分發版本的版本碼。增加主版本時也不可重設版本碼。

私有前端套件的 `version` 僅為工具套件版本，不決定 Manager 版本；發佈時以根目錄版本檔為準。

每次建立標籤前，先更新[更新紀錄](changelog.zh-TW.md)。發佈流程只接受與 `v` 加上 `versionName` 完全一致的標籤：

```sh
node scripts/version.mjs check-tag v1.0.0
```

## 發佈金鑰

在新專案或 fork 初次設定時執行：

```sh
node scripts/generate-signing-key.mjs
```

腳本會在 PKCS#12 金鑰庫中產生 4096 位元 RSA 金鑰，使用隨機密碼、SHA256withRSA 簽名，憑證有效期為 10,000 天。需要 JDK 的 `keytool`；若有設定 `JAVA_HOME`，優先使用 `JAVA_HOME/bin`。腳本不會覆寫既有金鑰庫或本機設定檔。

| 檔案 | 處理方式 |
| --- | --- |
| `.signing/yggdrasilmanager-release.p12` | 私鑰；排除於 Git；請私下備份。 |
| `keystore.properties` | 密碼與別名；排除於 Git；請與金鑰一同私下備份。 |
| `.signing/release-certificate.pem` | 產生的公開憑證。 |
| `signing/release-certificate.pem` | 提交到儲存庫的公開憑證，用於發佈驗證。 |

本儲存庫已產生發佈金鑰，後續更新請沿用。遺失金鑰會導致無法更新由該金鑰簽名的安裝版本；重新產生金鑰不是一般發佈步驟。請在工作區外保留加密備份，GitHub Secrets 無法匯出備份。

Fork 若自行產生金鑰，請在發佈前將公開憑證複製到 `signing/release-certificate.pem`。Gradle 不會要求貢獻者建置必須符合此憑證，但發佈流程會在公開產物前驗證。不可將私鑰庫複製到 `signing/`。

使用既有金鑰時，將 `keystore.properties.example` 複製為 `keystore.properties`，填妥四個欄位。`storeFile` 若非絕對路徑，則以儲存庫根目錄為基準；Windows 請使用正斜線。環境變數優先於本機設定：

| 變數 | 用途 |
| --- | --- |
| `YGGDRASIL_KEYSTORE_FILE` | 金鑰庫路徑 |
| `YGGDRASIL_KEYSTORE_PASSWORD` | 金鑰庫密碼 |
| `YGGDRASIL_KEY_ALIAS` | 簽名別名 |
| `YGGDRASIL_KEY_PASSWORD` | 金鑰密碼 |

Release 建置缺少任一簽名設定便會失敗，不會改用 debug 金鑰。程式碼縮減維持停用，因為 JNI 與 libsu Root 服務需先經過獨立的真機驗證，才能啟用。

## 本機建置與驗證

前端同步會用目前原始碼的建置結果取代 `app/src/main/assets/observe-web/`。每次封裝前都應重新建置：

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
node --test scripts/test/*.test.mjs
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

輸出位置：

- `app/build/outputs/apk/debug/app-debug.apk`：套件名稱為 `ng1ok.yggdrasilmanager.debug`，版本後綴為 `-debug`。
- `app/build/outputs/apk/release/app-release.apk`：已簽名的 `ng1ok.yggdrasilmanager` 套件。
- `app/build/reports/`：單元測試與 lint 報告。

舊開發版曾使用正式套件名稱搭配 debug 憑證。Android 無法以新的正式憑證直接更新這類安裝；安裝 release 前，請先保留需要的設定並移除舊版。新的 debug 版使用獨立的 `.debug` 名稱，不會發生此衝突。

使用 SDK 的 `build-tools/36.0.0` 內的 `apksigner` 與 `zipalign`（Windows 為 `apksigner.bat` 與 `zipalign.exe`）：

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
keytool -printcert -file signing/release-certificate.pem
```

將工具目錄加入 `PATH`，或使用絕對路徑呼叫，並在儲存庫根目錄執行。確認 APK 簽名的 SHA-256 摘要與公開憑證一致。發佈流程會自動執行此檢查，並為 APK 與公開憑證產生 `SHA256SUMS`。

## GitHub Actions 設定

儲存庫必須啟用 Actions。使用已登入的 GitHub CLI 設定儲存庫 Secrets：

```sh
node scripts/configure-github-signing.mjs ngiokweng/YggdrasilManager
```

Fork 請使用自己的 `OWNER/REPOSITORY`。腳本透過標準輸入傳送數值，更新以下四項 Actions Secrets，不會印出內容：

- `YGGDRASIL_KEYSTORE_BASE64`
- `YGGDRASIL_KEYSTORE_PASSWORD`
- `YGGDRASIL_KEY_ALIAS`
- `YGGDRASIL_KEY_PASSWORD`

`ngiokweng/YggdrasilManager` 已完成設定。不可將 `keystore.properties` 或私鑰作為 workflow artifact 上傳。正式簽名建置會停用持久化 Gradle 快取與 configuration cache，避免簽名資料被保存在上傳的快取中。

| Workflow | 觸發方式 | 結果 |
| --- | --- | --- |
| `CI` | 分支推送、PR 與手動執行 | 版本測試、npm audit、前端測試與建置、Android 測試、debug／release lint 與 APK 編譯；上傳 debug APK 與報告。 |
| `Release` | `v*` 標籤；以標籤手動重跑 | 檢查標籤與版本一致，執行測試、audit 與 lint，使用私鑰建置，驗證憑證與 16 KB ZIP 對齊，再將 APK、憑證與校驗檔發佈到 GitHub release。 |

PR（含 fork）會產生臨時簽名金鑰，不需要正式 Secrets。CI 的 release APK 僅用於編譯檢查，不對外分發。Actions 以 commit SHA 固定版本。Dependabot 的 Actions、Gradle 與 npm version-update PR 已透過 `open-pull-requests-limit: 0` 停用；security updates 的設定維持不變。

## 發佈

提交已審閱的原始碼、產生的瀏覽器資源、公開憑證、版本與更新紀錄，再推送分支。CI 通過後，才對同一個 commit 建立標籤。初始版本使用：

```sh
git tag -a v1.0.0 -m 'YggdrasilManager 1.0.0'
git push origin v1.0.0
```

後續發佈必須使用升版後的版本。`v1.1.0-rc.1` 這類標籤會建立 GitHub prerelease。既有 release 不會被覆寫，請勿移動已發佈標籤。Workflow 存在於預設分支後，可使用標籤作為 ref 手動重跑，例如 `gh workflow run release.yml --ref v1.0.0`。

Workflow 不會變更儲存庫可見性。將儲存庫公開前，請先選定專案授權並完成[開源待決事項](open-source-readiness.zh-TW.md)。Workflow 變更提交並推送後，才能驗證 GitHub 託管環境的實際執行結果。
