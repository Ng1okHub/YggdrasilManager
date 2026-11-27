# 瀏覽器控制台開發

[English](frontend.en.md) | **繁體中文** | [简体中文](frontend.zh-CN.md)

瀏覽器控制台位於 `frontend/`，以 Vite、Preact 與 TypeScript 開發。建置結果為靜態檔案，由 Android App 直接從 assets 目錄提供。

## 建置與同步

請使用 Node.js 22 或更新版本，在儲存庫根目錄執行：

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
```

`sync:android` 會先執行 TypeScript 檢查與 Vite 建置，再以 `frontend/dist/` 取代 `app/src/main/assets/observe-web/`。修改前端原始碼後，請在封裝 APK 前重新建置並同步。Android 建置方式請見[建置與發佈指南](../release/releasing.zh-TW.md)。

HWBP 與 Syscall 的 Ktor 伺服器共用同一份前端。瀏覽器依伺服器連接埠選擇功能：HWBP 為 `8765`，Syscall 為 `8766`。

## 本機開發

```sh
npm run dev --prefix frontend
```

以 Vite 在本機執行時，可用 `?feature=hwbp` 或 `?feature=syscall` 選擇功能。這只會切換介面；開發伺服器不提供 Android 後端，也不會產生觀察事件。

## 相依套件檢查

```sh
npm audit --prefix frontend --audit-level=high
```

CI 會依 `frontend/package-lock.json` 安裝相依套件，執行 audit 與測試，再重新建置並同步瀏覽器資源。手機端控制台的連線方式請見[使用指南](../user-guide/guide.zh-TW.md)。
