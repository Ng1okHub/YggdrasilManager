# HWBP Observe — Network Refactor Design

**Date:** 2026-07-16
**Status:** Approved

## Summary

Refactor the HWBP Observe feature so the Android app exposes an embedded HTTP/WebSocket server, and all operation & event observation moves to a PC-side web page served from APK assets. The app UI reduces to a minimal status page.

---

## Architecture

```
Kernel KPM
  └─ prctl(PR_YGGDRASIL_HWBP_READ_EVENT)
       └─ JNI pthread polling loop  (ygg_jni.cpp — unchanged)
            └─ HwbpEventCallback
                 └─ YggdrasilRepository._hwbpEvents (SharedFlow)
                      └─ HwbpWebServer  ← subscribes, broadcasts to all WS sessions
                           ├─ Ktor CIO engine, port 8765
                           ├─ GET /         → assets/web/index.html
                           ├─ GET /static/* → assets/web/*
                           └─ GET /ws       → WebSocket sessions

PC Browser (index.html + app.js)
  └─ ws://<phone-IP>:8765/ws  (auto-reconnect on disconnect)
       ├─ Receive: state, event
       └─ Send:    observe, unobserve
```

The `YggdrasilRepository` and JNI layer are **unchanged**. All new logic lives in `HwbpWebServer`.

---

## Components

### HwbpWebServer (`ui/hwbp/HwbpWebServer.kt`) — new

Responsibilities:
- Hold a Ktor `ApplicationEngine` (CIO). `start()` / `stop()` control its lifecycle.
- Serve static files from `assets/web/` via the `context.assets` API.
- Accept WebSocket connections on `/ws`.
- On new connection: send a `state` message with current observe points (provided via a lambda/callback from ViewModel).
- Subscribe to `YggdrasilRepository.hwbpEvents` SharedFlow; for each event, serialize to JSON and send to all active sessions.
- On incoming client message: parse `type`, call `repo.hwbpObserve` or `repo.hwbpUnobserve`, reply with `ack`.
- Expose `connectedClients: StateFlow<Int>` for the App status UI.

### HwbpViewModel (`ui/hwbp/HwbpViewModel.kt`) — modified

Changes:
- Remove `_events: StateFlow<List<HwbpEvent>>` and all event-collection logic (events go to the web client now).
- On `init`: instantiate and start `HwbpWebServer`.
- On `onCleared()`: stop `HwbpWebServer` (in addition to existing `repo.stopEventStream()`).
- Keep `observePoints: StateFlow<List<HwbpObserveParams>>` — the server reads this to build the `state` message for new connections.
- `addObserve` / `removeObserve` remain; they update `observePoints` and also trigger a `state` broadcast so connected clients stay in sync.

### HwbpScreen (`ui/hwbp/HwbpScreen.kt`) — modified

Replace two-tab layout with a single status page showing:
- Device Wi-Fi IP address and port (e.g. `ws://192.168.1.5:8765`)
- Number of currently connected PC clients
- Start / Stop server toggle button
- Count of active observe points

### Web UI (`app/src/main/assets/web/`) — new

Two files, no build toolchain, no external dependencies:

- `index.html` — page shell, two-column layout
- `app.js` — WebSocket client, DOM rendering, event handling

**Layout:**
```
┌──────────────────────┬──────────────────────┐
│  Observe Points      │  Events       [Clear] │
│  [+ Add form]        │                       │
│  • pid / addr / mode │  #seq comm tid mode   │
│    [Remove]          │  addr ▶ (expand regs) │
└──────────────────────┴──────────────────────┘
```

Behaviour:
- Auto-connect to `ws://<same host>:8765/ws` on load; retry after 3 s on disconnect.
- On `state`: render observe points list.
- On `event`: prepend to events list, cap at 500 entries, auto-scroll to top.
- Add form fields: addr (hex), PID, mode (R/W/RW/X buttons), len (1/2/4/8 buttons). Submit sends `observe`, waits for `ack`.
- Remove button sends `unobserve` for that entry.

---

## WebSocket Message Protocol

All messages are UTF-8 JSON. `type` is always present.

### Client → Server

```json
// Add observe point
{ "type": "observe", "pid": 1234, "addr": "0x7ff0001000",
  "mode": "RW", "len": 8, "singleThread": false, "stackSize": 256 }

// Remove observe point
{ "type": "unobserve", "pid": 1234, "addr": "0x7ff0001000" }
```

### Server → Client

```json
// Sent immediately on new connection; also broadcast after any add/remove
{ "type": "state",
  "points": [{ "pid": 1234, "addr": "0x7ff0001000", "mode": "RW", "len": 8 }] }

// Fired for each kernel HWBP event
{ "type": "event", "seq": 42, "dropped": 0, "comm": "target_app",
  "tid": 5678, "tgid": 5678, "watchedAddr": "0x7ff0001000",
  "pc": "0x71a3b000", "sp": "0x7ffd000000", "regs": [0,0,...] }

// Response to observe / unobserve commands
{ "type": "ack", "ok": true, "error": null }
```

---

## Dependencies

Add to `app/build.gradle.kts`:
```kotlin
implementation("io.ktor:ktor-server-core:2.3.x")
implementation("io.ktor:ktor-server-cio:2.3.x")
implementation("io.ktor:ktor-server-websockets:2.3.x")
implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.x")
```

Ktor CIO runs on coroutines; no additional threading primitives needed.

---

## What Does NOT Change

- `yggdrasil.h` — KPM ABI
- `ygg_jni.cpp` — JNI bridge and pthread polling loop
- `YggdrasilNative.kt` — JNI declarations
- `YggdrasilRepository.kt` — SharedFlow, prctl wrappers
- `HwbpObserveParams.kt`, `HwbpEvent.kt` — data models
- HideDbg feature — entirely unaffected

---

## Out of Scope

- Authentication (LAN-only, no auth required)
- Event persistence across app restarts
- Multiple simultaneous HWBP configurations (existing limit unchanged)
