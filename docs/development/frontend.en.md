# Browser-console development

**English** | [繁體中文](frontend.zh-TW.md) | [简体中文](frontend.zh-CN.md)

The browser console is a Vite + Preact + TypeScript application in `frontend/`. It builds to static files because the Android app serves them directly from its assets directory.

## Build and sync

Run these commands from the repository root with Node.js 22 or later:

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
```

`sync:android` first runs TypeScript checks and a Vite build, then replaces `app/src/main/assets/observe-web/` with `frontend/dist/`. Changes to frontend source must be rebuilt and synced before packaging an APK. See the [build and release guide](../release/releasing.en.md) for Android builds.

Both the HWBP and Syscall Ktor servers serve the same bundle. The browser selects its feature from the server port: `8765` for HWBP, `8766` for Syscall.

## Local development

```sh
npm run dev --prefix frontend
```

When running locally with Vite, select the feature using `?feature=hwbp` or `?feature=syscall`. This chooses the interface; the development server does not provide the Android backend or generate observation events.

## Dependency checks

```sh
npm audit --prefix frontend --audit-level=high
```

CI installs dependencies from `frontend/package-lock.json`, runs the audit and tests, then rebuilds and syncs the browser assets. See the [user guide](../user-guide/guide.en.md) for on-device console access.
