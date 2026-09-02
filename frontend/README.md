# Yggdrasil Observe Web

The browser console is a Vite + Preact + TypeScript application.  It is built
as static files because the Android app serves the result directly from its
assets directory.

```powershell
npm install
npm test
npm run sync:android
```

The sync command copies the generated `dist` directory to
`app/src/main/assets/observe-web/`. Both the HWBP and Syscall Ktor servers
serve this same bundle; the browser selects the feature from the server port
(`8765` for HWBP and `8766` for Syscall), or from `?feature=hwbp|syscall` when
running locally with Vite.
