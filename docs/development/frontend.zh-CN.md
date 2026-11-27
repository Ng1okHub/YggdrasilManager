# 浏览器控制台开发

[English](frontend.en.md) | [繁體中文](frontend.zh-TW.md) | **简体中文**

浏览器控制台位于 `frontend/`，使用 Vite、Preact 与 TypeScript 开发。构建结果为静态文件，由 Android App 直接从 assets 目录提供。

## 构建与同步

请使用 Node.js 22 或更新版本，在仓库根目录运行：

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
```

`sync:android` 会先执行 TypeScript 检查与 Vite 构建，再用 `frontend/dist/` 替换 `app/src/main/assets/observe-web/`。修改前端源码后，请在打包 APK 前重新构建并同步。Android 构建方式请见[构建与发布指南](../release/releasing.zh-CN.md)。

HWBP 与 Syscall 的 Ktor 服务器共用同一份前端。浏览器根据服务器端口选择功能：HWBP 为 `8765`，Syscall 为 `8766`。

## 本地开发

```sh
npm run dev --prefix frontend
```

通过 Vite 在本地运行时，可用 `?feature=hwbp` 或 `?feature=syscall` 选择功能。这只会切换界面；开发服务器不提供 Android 后端，也不会生成观察事件。

## 依赖检查

```sh
npm audit --prefix frontend --audit-level=high
```

CI 会按照 `frontend/package-lock.json` 安装依赖，执行 audit 与测试，再重新构建并同步浏览器资源。手机端控制台的连接方式请见[使用指南](../user-guide/guide.zh-CN.md)。
