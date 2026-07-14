# YggdrasilManager 設計文檔

**日期：** 2026-07-14  
**項目：** YggdrasilManager — Yggdrasil KPM 內核模塊的 Android 管理器

---

## 1. 背景

Yggdrasil 是一個 KernelPatch Module（KPM），通過自定義 `prctl()` 命令向用戶態提供增強能力。YggdrasilManager 是用於操作這些接口的 Android 管理應用。

**目標接口：**
- `PR_YGGDRASIL_HWBP_OBSERVE / UNOBSERVE / READ_EVENT`（硬件斷點觀察）
- `PR_YGGDRASIL_HIDE_DBG`（隱藏調試器）

---

## 2. 技術棧

| 層 | 技術 |
|---|---|
| UI | Kotlin + Jetpack Compose + Material3 |
| 狀態管理 | ViewModel + StateFlow |
| 原生層 | C++ JNI（NDK） |
| Root | libsu 5.x |
| 構建 | AGP + CMake |

從現有 Java 骨架遷移至 Kotlin，啟用 Compose。

---

## 3. 整體架構

```
UI Layer (Compose Screens)
        ↕ StateFlow / events
ViewModel Layer (per-screen)
        ↕ suspend fun / Flow
YggdrasilRepository (singleton, Dispatchers.IO)
        ↕ JNI calls
Native Layer (ygg_jni.cpp)
        ↕ prctl() with yggdrasil.h structs
Kernel — Yggdrasil KPM
```

Root 提權：啟動時通過 libsu 請求 root，授權後 app 進程本身獲得 root 權限，JNI 層 `prctl()` 直接調用，無需額外 root service。

---

## 4. 導航結構

Bottom Navigation — 2 個 Tab：

```
主頁 (HomeScreen)
功能 (FeaturesScreen)
    └─ Feature Card 列表
       ├─ HWBP Observe  → HwbpScreen
       │     ├─ Observe Tab（observe 點管理）
       │     └─ Events Tab（實時事件流）
       └─ Hide DBG       → HideDbgScreen
             ├─ 全局開關
             └─ per-UID 規則列表
```

FeaturesScreen 作為功能入口登記中心，後續新增功能只需添加一個 Card，無需改動導航骨架。

---

## 5. 頁面設計

### HomeScreen
- 模塊可用性狀態卡片（調用 `PR_YGGDRASIL_AVAILABLE`）
- Root 狀態卡片
- 每張卡片帶圖標、標題、狀態文字

### FeaturesScreen
- Card 列表（或 Grid），每個功能顯示圖標 + 名稱 + 一行描述
- 點擊進入對應功能頁

### HwbpScreen（兩個 Tab）

**Observe Tab：**
- 當前 observe 點列表（內存維護，顯示 pid、addr、mode、len）
- 每條記錄右側有「刪除」按鈕（調用 unobserve）
- FAB（+）打開底部 Sheet 填寫新 observe：
  - Target address（hex 輸入）
  - Target PID
  - Mode（r / w / rw / x，選擇器）
  - Len（1 / 2 / 4 / 8，選擇器；mode=x 時固定為 4）
  - Flags（Single Thread toggle、Filter System toggle）
  - Stack size（數字輸入，默認 4096，最大 65536）

**Events Tab：**
- 實時滾動列表，保留最近 200 條事件
- 每條顯示：seq、tid/tgid、comm、mode、watched addr、pc
- 展開可查看完整寄存器（regs[31]、sp）
- 右上角清空按鈕

### HideDbgScreen
- 頂部：全局開關 Card（Switch）
- 下方：per-UID 規則列表（顯示 UID，右側刪除按鈕）
- FAB（+）添加新 UID 規則（數字輸入對話框）

---

## 6. 數據類

```kotlin
data class HwbpObserveParams(
    val pid: Int,
    val targetAddr: Long,
    val mode: Int,      // YGG_HWBP_MODE_R/W/RW/X
    val len: Int,       // 1/2/4/8
    val flags: Int,     // YGG_HWBP_F_*
    val stackSize: Int
)

data class HwbpEvent(
    val seq: Long,
    val dropped: Long,
    val tid: Int,
    val tgid: Int,
    val comm: String,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val watchedAddr: Long,
    val pc: Long,
    val sp: Long,
    val regs: LongArray
)
```

---

## 7. JNI 層（YggdrasilNative.kt + ygg_jni.cpp）

```kotlin
object YggdrasilNative {
    external fun checkAvailable(): Boolean
    external fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int,
                              len: Int, flags: Int, stackSize: Int): Int
    external fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    external fun hwbpStartListen(callback: HwbpEventCallback)
    external fun hwbpStopListen()
    external fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
}

interface HwbpEventCallback {
    fun onEvent(seq: Long, dropped: Long, tid: Int, tgid: Int, comm: String,
                mode: Int, len: Int, flags: Int, watchedAddr: Long,
                pc: Long, sp: Long, regs: LongArray)
}
```

`yggdrasil.h` 直接 copy 至 `app/src/main/cpp/`，JNI 層複用其結構體定義。

JNI 後台線程：輪詢 `PR_YGGDRASIL_HWBP_READ_EVENT`，EAGAIN 時 sleep 100ms，收到事件後通過 `CallVoidMethod` 回調 Kotlin。

---

## 8. Repository 層

```kotlin
class YggdrasilRepository {
    suspend fun checkAvailable(): Boolean
    suspend fun hwbpObserve(params: HwbpObserveParams): Result<Unit>
    suspend fun hwbpUnobserve(pid: Int, targetAddr: Long): Result<Unit>
    val hwbpEvents: SharedFlow<HwbpEvent>  // 懶啟動，首個 subscriber 觸發 startListen
    suspend fun setHideDbg(enable: Boolean, uid: Int?): Result<Unit>
}
```

所有 JNI 調用運行在 `Dispatchers.IO`。錯誤通過 `Result<Unit>` 返回，包含 errno 信息。

---

## 9. ViewModel 層

| ViewModel | 核心 StateFlow | 主要 Action |
|---|---|---|
| `HomeViewModel` | `uiState: HomeUiState`（root狀態、模塊可用性） | `refresh()` |
| `HwbpViewModel` | `observePoints: List<HwbpObserveParams>`、`events: List<HwbpEvent>`（max 200） | `addObserve()`, `removeObserve()`, `clearEvents()` |
| `HideDbgViewModel` | `globalEnabled: Boolean`、`uidRules: List<Int>` | `toggle()`, `addUid()`, `removeUid()` |

---

## 10. Root 集成

```kotlin
// Application.onCreate()
Shell.enableVerboseLogging = BuildConfig.DEBUG
Shell.setDefaultBuilder(Shell.Builder.create()
    .setFlags(Shell.FLAG_REDIRECT_STDERR)
    .setTimeout(10))
```

啟動流程：
1. `HomeViewModel.refresh()` → `Shell.getShell()` → 系統彈出 root 授權
2. 授權成功 → 功能頁正常操作
3. 授權失敗 → HomeScreen 顯示警告，功能頁按鈕禁用

---

## 11. 文件結構

```
app/src/main/
├── cpp/
│   ├── yggdrasil.h         (從 KPM 源碼 copy)
│   ├── ygg_jni.cpp
│   └── CMakeLists.txt
└── kotlin/ng1ok/yggdrasilmanager/
    ├── App.kt
    ├── MainActivity.kt
    ├── native/
    │   └── YggdrasilNative.kt
    ├── data/
    │   ├── model/           (HwbpEvent.kt, HwbpObserveParams.kt)
    │   └── YggdrasilRepository.kt
    └── ui/
        ├── theme/           (Theme.kt, Color.kt, Type.kt)
        ├── navigation/      (NavGraph.kt, BottomNavItem.kt)
        ├── home/            (HomeScreen.kt, HomeViewModel.kt)
        ├── features/        (FeaturesScreen.kt)
        ├── hwbp/            (HwbpScreen.kt, HwbpViewModel.kt)
        └── hidedbg/         (HideDbgScreen.kt, HideDbgViewModel.kt)
```

---

## 12. 不在範圍內

- HWBP Attach / Replace / Detach
- `PR_YGGDRASIL_MEMFD_CREATE`
- `PR_YGGDRASIL_VMA_MERGE_VIEW`
- 持久化 observe 點（重啟後清空）
- 符號解析（lib + offset → addr）的 UI（可後續擴展）
