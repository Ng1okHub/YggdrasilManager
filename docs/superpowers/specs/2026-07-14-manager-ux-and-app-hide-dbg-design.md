# YggdrasilManager UX 與按 App Hide DBG 設計

**日期：** 2026-07-14
**狀態：** 已確認，待實作計畫

## 1. 目標

本次優化包含四項可獨立驗收的行為：

1. App 啟動並完成 Root 檢查後，若未取得 Root，主動顯示一次應用內提示。
2. 主頁改為接近 KernelSU／APatch 管理器的緊湊資訊首頁，展示模組可用性、Root 狀態、設備資訊與 Manager 版本。
3. HWBP 與 Hide DBG 子頁提供明確返回鍵，並在子頁隱藏底部導航。
4. Hide DBG 遍歷目前 Android 使用者的全部已安裝 App，支援搜尋、使用者／系統篩選及按 App 開關。

## 2. 已確認的產品決策

- Root 未授權 Dialog 只作提示，不會停用或阻止任何功能。
- Root 狀態與 Yggdrasil 可用性獨立檢查；即使沒有 Root，仍檢查並展示模組狀態。
- 目前 ABI 無法查詢已安裝 Yggdrasil 版本，主頁暫時只顯示可用性，不顯示推測版本。
- Hide DBG 狀態保存在 App 私有配置檔。App 進程啟動時讀取配置並快取，Hide DBG 畫面只取得快取 snapshot 以還原 UI，絕不向 KPM 重新套用規則。
- App 清單範圍是目前 Android 使用者下的全部套件，包含使用者 App 與系統 App；不跨其他使用者或工作資料夾。
- KPM 按 UID 而非 package name 控制。共享 UID 的 App 分列展示，但 Switch 必須同步。
- 關閉全局 Hide DBG 會依 KPM 現有語義清除全部 UID 規則；成功後配置與 UI 也一併清空。

## 3. 現有系統限制

### 3.1 Root 與 KPM 呼叫

`Shell.getShell()` 取得的是獨立的 root shell，不會提升 Android App 進程 UID。現有 JNI `prctl()` 仍由 App 進程呼叫。本次工作不改變 KPM 的安全模型或 JNI 執行方式；Root 僅作管理器狀態提示。

### 3.2 Yggdrasil 版本

`PR_YGGDRASIL_AVAILABLE` 只回傳可用性，public ABI 沒有版本查詢命令。KPM 原始碼中的 `KPM_VERSION("0.3.0")` 不能可靠代表設備實際載入版本，因此不在 UI 顯示。

### 3.3 Hide DBG 狀態查詢

KPM 只提供寫入全局／UID 規則的命令，沒有 GET 或 LIST。配置檔代表「Manager 最後一次成功送出的狀態」，不是可向 KPM 驗證的真實快照。外部 CLI 修改、KPM 重新載入或其他進程修改規則後，Manager 無法自動得知差異。

### 3.4 全局關閉語義

KPM 的 `enable=false, hasUid=false` 會同時關閉全局模式並清除所有 UID 規則。Manager 不會在此操作後重放 UID 規則。

## 4. 架構

沿用現有 Compose + ViewModel + Repository 分層，新增兩個小型資料邊界，不引入第三方依賴。

### 4.1 Root 與首頁狀態

首頁狀態包含：

- Root：`Checking`、`Granted`、`Denied`
- Yggdrasil：`Checking`、`Available`、`Unavailable`
- 設備資訊：製造商／型號、Android 版本與 API、核心版本、Manager 版本
- 本次 App 啟動是否已顯示 Root 未授權提示

Root 與模組檢查並行且互不依賴。Root 拒絕後產生一次可消費的提示狀態；關閉 Dialog 只清除提示，不改變 Root 狀態或導航能力。

設備資訊由可注入的 provider 提供，避免 ViewModel 直接散落 `Build` 與 `BuildConfig` 存取，也讓 JVM 測試可使用固定資料。

### 4.2 InstalledAppsSource

新增 `InstalledAppsSource`，在 `Dispatchers.IO` 透過 `PackageManager` 取得目前使用者可見的全部 `ApplicationInfo`，轉換為不依賴 Android Drawable 的資料模型：

```kotlin
data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean
)
```

系統 App 判定同時考慮 `FLAG_SYSTEM` 與 `FLAG_UPDATED_SYSTEM_APP`。清單按顯示名稱排序，再以 package name 作穩定排序。圖標只在可見列表列中按 package name 延遲載入，失敗時使用通用 App 圖標。

Manifest 加入 `android.permission.QUERY_ALL_PACKAGES`，因為需求包含沒有 Launcher Activity 的套件。此權限可能受 Google Play 政策限制；本工具定位為需要核心模組的裝置管理器，發佈時仍需依目標渠道檢查政策。

### 4.3 HideDbgConfigStore

使用 Android `SharedPreferences` 建立 App 私有配置檔 `shared_prefs/hide_dbg_config.xml`。`App.onCreate()` 在進程啟動時建立 store、讀取配置並快取不可變 snapshot；此步驟不依賴任何畫面，也不呼叫 KPM。Schema v1 包含：

| Key | 型別 | 語義 |
|---|---|---|
| `schema_version` | Int | 固定為 `1` |
| `global_enabled` | Boolean | Manager 最後一次成功設定的全局狀態 |
| `enabled_uids` | StringSet | Manager 最後一次成功啟用的十進位 UID 集合 |

讀取時忽略非十進位、負數或超出 Int 範圍的 UID。無法讀取或 schema 不支援時回退為 `global=false`、空 UID 集合並回報可顯示錯誤。

寫入使用可回報成功／失敗的同步 `commit()`，但在 IO dispatcher 執行。只有 KPM 操作成功後才寫配置。配置寫入失敗不會假裝回滾已成功的 KPM 操作。

### 4.4 HideDbgUiState

Hide DBG 使用單一不可變 UI state，至少包含：

```kotlin
data class HideDbgUiState(
    val globalEnabled: Boolean,
    val apps: List<InstalledApp>,
    val enabledUids: Set<Int>,
    val query: String,
    val filter: AppFilter,
    val loadingApps: Boolean,
    val globalPending: Boolean,
    val pendingUids: Set<Int>,
    val message: UiMessage?
)
```

Hide DBG 畫面建立時直接取得 Application 已載入的配置 snapshot，並與 App 清單載入並行組合成 UI state。任何初始化流程都不得呼叫 `setHideDbg()` 或其他會改變 KPM 狀態的方法。

## 5. Hide DBG 資料流

### 5.1 按 App 切換

1. 從所選 App 取得 UID。
2. 若全局操作中、全局模式已開啟，或該 UID 已在處理中，忽略重複操作。
3. 將 UID 加入 `pendingUids`，因此所有共享 UID 列同時停用。
4. 呼叫現有 `YggdrasilRepository.setHideDbg(enable, uid)`。
5. KPM 失敗時移除 pending，保留原狀並顯示錯誤。
6. KPM 成功時更新 `enabledUids`，再寫入配置。
7. 配置成功後完成；配置失敗時保留新的 UI／核心狀態並提示「已套用但未保存」。

同 UID 的所有 Switch 都由 `enabledUids.contains(uid)` 派生，不各自保存 package 狀態。

### 5.2 全局切換

開啟全局：

- 呼叫 `setHideDbg(true, null)`。
- 成功後保存 `global_enabled=true`。
- 現有 `enabled_uids` 保留，因為 KPM 開啟全局不會刪除 UID 規則；App Switch 顯示其規則值但不可操作。

關閉全局：

- 呼叫 `setHideDbg(false, null)`。
- 成功後設為 `global_enabled=false`，並將 `enabled_uids` 清空後一起保存。
- 不重放關閉前的任何 UID 規則。

全局操作期間，全部 Switch 均停用，避免全局清除與 UID 寫入交錯。

### 5.3 搜尋與篩選

- 搜尋同時匹配 App 名稱與 package name，不區分大小寫。
- 篩選選項為「全部」、「使用者」、「系統」。
- 搜尋與篩選只改變可見清單，不改變配置或 KPM。
- 畫面提供刷新圖標，重新遍歷 App；刷新也不改變 KPM 或配置。

## 6. 導航與 App Shell

主 Scaffold 根據目前 route 產生一致的 chrome：

| Route | Top App Bar | 返回鍵 | Bottom Navigation |
|---|---|---|---|
| `home` | 主頁標題 | 無 | 顯示 |
| `features` | 功能標題 | 無 | 顯示 |
| `hwbp` | HWBP Observe | 顯示 | 隱藏 |
| `hidedbg` | Hide DBG | 顯示 | 隱藏 |

子頁返回箭頭呼叫 `navController.navigateUp()`，結果與系統 Back 一致。底部導航只存在於頂層 route，因此子頁不再出現兩個 tab 都未選中的狀態，也不會透過 tab state 恢復到舊子頁。

route 的標題、是否頂層、是否顯示返回鍵集中在單一 route metadata 定義，避免 Features registry 與 NavGraph 繼續散落魔法字串。

## 7. 視覺設計

### 7.1 Theme

- 保留 Material 3 元件與明暗模式。
- 移除範本紫／粉色組合，改用綠色作正向與主要操作色、冷灰作中性表面、獨立紅色作錯誤色。
- 使用一致的自訂 light/dark scheme，不由動態色覆蓋產品狀態語義。
- Card／Surface 圓角不超過 8dp；資訊區維持緊湊，不使用巢狀卡片。

### 7.2 主頁

主頁使用可捲動 `LazyColumn`，避免小螢幕或大字體截斷。內容順序：

1. 模組狀態區：App 圖標、Yggdrasil 名稱、可用／不可用／檢查中。
2. Root 狀態列：已授權／未授權／檢查中。
3. 設備資訊區：設備名稱、Android/API、核心版本、Manager 版本。

不顯示 Yggdrasil 版本。狀態文字與圖標並用，不只依賴顏色。所有長值可換行或省略，不得擠壓尾端狀態。

Root 未授權 Dialog 每次 App 啟動最多顯示一次，提供明確的「知道了」按鈕，可正常 dismiss；不改變任何功能可用性。

### 7.3 Hide DBG

畫面由以下順序組成：

1. 全局 Hide DBG 設定列。
2. 搜尋欄與全部／使用者／系統篩選。
3. App `LazyColumn`。

每個 App row 使用穩定高度與固定圖標尺寸，展示圖標、App 名稱、package name、UID、共享 UID 標記與 Switch。名稱及 package name 使用單行省略；共享 UID 標記只在同一載入清單中有兩個以上 package 使用該 UID 時顯示。

列表具備載入、載入失敗／重試及無搜尋結果狀態。全局開啟時保留列表供檢視，但 App Switch 停用。

## 8. 錯誤與競態處理

- App 清單載入失敗：保留可重試錯誤狀態，不影響全局開關。
- KPM UID 操作失敗：原 Switch 不變，所有共享 UID 列恢復可操作並顯示錯誤。
- KPM 全局操作失敗：保留全局與 UID 原狀並恢復操作。
- 配置保存失敗：保留已成功生效的核心／UI 狀態，顯示「已套用但未保存」。
- 快速重複點擊：以 `globalPending` 與 `pendingUids` 拒絕相同或互斥操作，不排隊執行過時請求。
- App 卸載後配置中的 UID 不主動刪除，因為 Manager 無法確認 KPM 是否仍保留該規則；若 UID 日後被重用，UI 會如實依保存 UID 顯示啟用。
- 外部 CLI 或 KPM reload 導致的配置漂移不在本次可解決範圍內。

## 9. 測試策略

所有行為改動採測試先行。

### 9.1 JVM 單元測試

- Root 拒絕後產生一次提示；dismiss 後不重複；模組檢查仍執行。
- Root 與模組檢查中的狀態轉換及設備資訊映射。
- route metadata：頂層顯示底部導航，子頁顯示返回鍵並隱藏底部導航。
- App 名稱排序、名稱／package 搜尋、使用者／系統篩選。
- shared UID 派生相同 checked 與 pending 狀態。
- Hide DBG 初始化讀取配置，但零次呼叫 KPM setter。
- UID 開關成功、KPM 失敗、配置保存失敗及重複操作。
- 全局開啟保留 UID；全局關閉成功清空 UID；全局失敗保留原狀。
- 配置 schema、非法 UID 過濾與 commit 失敗。

### 9.2 建置與靜態檢查

- 執行完整 JVM 測試。
- 執行 Debug APK 建置，包含 Kotlin、Compose 與 JNI/CMake 編譯。
- 執行 Android lint。
- 執行 `git diff --check`。

### 9.3 裝置驗收

如有可用 Android 裝置／模擬器，驗證：

- Root 拒絕後 Dialog 出現且功能仍可進入。
- 主頁在明暗模式、小螢幕及較大字體下無重疊。
- 子頁返回箭頭與系統 Back 都返回功能列表。
- App 清單包含使用者與系統 App，搜尋／篩選正確。
- shared UID Switch 同步。
- Manager 重啟後從配置恢復 UI，且啟動期間不送出 Hide DBG KPM 命令。
- 關閉全局模式後全部 App Switch 清空。

## 10. 非目標

- 修改 Yggdrasil KPM ABI、增加版本或規則查詢命令。
- 透過 RootService 或 root CLI 執行 JNI 操作。
- 啟動時重新套用 Hide DBG 配置。
- 跨 Android 使用者／工作資料夾列舉 App。
- 自動監聽 package 安裝／移除廣播；本次以進入畫面與手動刷新重新載入。
- 解決外部 CLI 與 Manager 配置的雙向同步。

## 11. 驗收標準

1. 未授權 Root 時，每次 App 啟動最多出現一次提示，關閉後所有功能仍可使用。
2. 主頁可看到 Yggdrasil 可用性、Root、設備、Android、核心與 Manager 版本資訊，不顯示虛構的模組版本。
3. HWBP 與 Hide DBG 均有可見返回箭頭，且子頁不顯示底部導航。
4. Hide DBG 可列出目前使用者的全部 App，支援搜尋與使用者／系統篩選。
5. App Switch 成功後按 UID 更新；共享 UID App 同步；失敗時不改變 checked 狀態。
6. Manager 重啟後由配置恢復 UI，初始化過程不寫入 KPM。
7. 關閉全局 Hide DBG 後，配置及 UI 的全部 UID 規則清空。
8. 單元測試、Debug build、lint 與 diff check 全部通過。
