export const EVENT_LIMIT = 1000;
export const RECONNECT_DELAY_MS = 3000;
export const MAX_UID = 4294967295;
export const MAX_PROCESS_ID = 2147483647;

const SCOPES = new Set(["tgid", "tid", "uid"]);
const PHASES = new Set(["enter", "exit", "both"]);
const SCOPE_LABELS = {
  tgid: "Process（TGID）",
  tid: "Thread（TID）",
  uid: "App（UID）",
  global: "所有處理程序",
};
const TARGET_LABELS = {
  tgid: "Process ID（TGID）",
  tid: "Thread ID（TID）",
  uid: "App ID（UID）",
};
const PHASE_LABELS = {
  enter: "Enter",
  exit: "Exit",
  both: "Both",
  complete: "Done",
};
const SYSCALL_NAMES = new Map([
  [20, "epoll_create1"], [21, "epoll_ctl"], [22, "epoll_pwait"],
  [23, "dup"], [24, "dup3"], [25, "fcntl"], [29, "ioctl"],
  [35, "unlinkat"], [37, "linkat"], [40, "mount"], [43, "statfs"],
  [46, "ftruncate"], [48, "faccessat"], [49, "chdir"], [50, "fchdir"],
  [52, "fchmod"], [53, "fchmodat"], [54, "fchownat"], [55, "fchown"],
  [56, "openat"],
  [57, "close"], [59, "pipe2"], [61, "getdents64"], [62, "lseek"],
  [63, "read"], [64, "write"], [65, "readv"], [66, "writev"],
  [67, "pread64"], [68, "pwrite64"], [69, "preadv"], [70, "pwritev"],
  [71, "sendfile"], [72, "pselect6"], [73, "ppoll"], [78, "readlinkat"],
  [79, "fstatat"], [80, "fstat"], [88, "utimensat"], [93, "exit"],
  [94, "exit_group"], [98, "futex"], [101, "nanosleep"],
  [113, "clock_gettime"], [129, "kill"], [131, "tgkill"],
  [134, "rt_sigaction"], [135, "rt_sigprocmask"], [139, "rt_sigreturn"],
  [160, "uname"], [167, "prctl"], [169, "gettimeofday"],
  [172, "getpid"], [173, "getppid"], [174, "getuid"],
  [175, "geteuid"], [176, "getgid"], [177, "getegid"], [178, "gettid"],
  [198, "socket"], [200, "bind"], [201, "listen"], [202, "accept"],
  [203, "connect"], [204, "getsockname"], [205, "getpeername"],
  [206, "sendto"], [207, "recvfrom"], [208, "setsockopt"],
  [209, "getsockopt"], [210, "shutdown"], [211, "sendmsg"],
  [212, "recvmsg"], [214, "brk"], [215, "munmap"], [220, "clone"],
  [221, "execve"], [222, "mmap"], [226, "mprotect"], [242, "accept4"],
  [260, "wait4"], [261, "prlimit64"], [278, "getrandom"],
  [281, "execveat"], [291, "statx"],
]);

export function syscallName(syscallNr) {
  return SYSCALL_NAMES.get(Number(syscallNr)) ?? `syscall_${syscallNr}`;
}

export function initialModel() {
  return {
    rules: [],
    events: [],
    listener: { state: "starting", error: null },
    supportedSyscalls: [],
    nextDisplayId: 1,
  };
}

export function reduceServerMessage(model, message) {
  if (message?.type === "state") {
    return {
      ...model,
      rules: Array.isArray(message.rules) ? [...message.rules] : [],
      listener: normalizeListener(message.listener, model.listener),
      supportedSyscalls: Array.isArray(message.supportedSyscalls)
        ? normalizeSupportedSyscalls(message.supportedSyscalls)
        : model.supportedSyscalls,
    };
  }
  if (message?.type !== "event") return model;

  const displayId = Number.isInteger(model.nextDisplayId) && model.nextDisplayId > 0
    ? model.nextDisplayId
    : 1;
  const existingIndex = findMatchingEntry(model.events, message);
  if (existingIndex >= 0) {
    const existing = model.events[existingIndex];
    const entryCaptures = Array.isArray(existing.captures) ? existing.captures : [];
    const exitCaptures = Array.isArray(message.captures) ? message.captures : [];
    const completed = {
      ...existing,
      ...message,
      displayId: existing.displayId,
      phase: "complete",
      entrySeq: existing.seq,
      exitSeq: message.seq,
      args: Array.isArray(existing.args) ? existing.args : message.args,
      argsBefore: Array.isArray(existing.argsBefore) ? existing.argsBefore : message.argsBefore,
      captures: [...entryCaptures, ...exitCaptures],
      afterDropped: Boolean(existing.afterDropped || message.afterDropped),
    };
    return {
      ...model,
      events: [completed, ...model.events.filter((_, index) => index !== existingIndex)]
        .slice(0, EVENT_LIMIT),
    };
  }
  return {
    ...model,
    nextDisplayId: displayId + 1,
    events: [{ ...message, displayId }, ...model.events].slice(0, EVENT_LIMIT),
  };
}

function normalizeListener(listener, fallback) {
  if (!listener || typeof listener !== "object") return fallback;
  const state = ["stopped", "starting", "running", "failed"].includes(listener.state)
    ? listener.state
    : "starting";
  return {
    state,
    error: typeof listener.error === "string" && listener.error ? listener.error : null,
  };
}

export function normalizeSupportedSyscalls(value) {
  if (!Array.isArray(value)) return [];
  const byNumber = new Map();
  value.forEach((entry) => {
    const nr = Number(entry?.nr);
    if (!Number.isSafeInteger(nr) || nr < 0 || nr > 459) return;
    const name = typeof entry?.name === "string" && entry.name.trim()
      ? entry.name.trim()
      : syscallName(nr);
    byNumber.set(nr, { nr, name });
  });
  return [...byNumber.values()].sort((first, second) => first.nr - second.nr);
}

function findMatchingEntry(events, incoming) {
  if (incoming?.phase !== "exit" || !usableCallId(incoming.callId)) return -1;
  return events.findIndex((event) =>
    event.phase === "enter" &&
    event.callId === incoming.callId &&
    event.tid === incoming.tid &&
    event.syscallNr === incoming.syscallNr,
  );
}

function usableCallId(callId) {
  return typeof callId === "string" && callId !== "0x0" && callId !== "0x";
}

export function clearEventsModel(model) {
  return {
    ...model,
    events: [],
    nextDisplayId: 1,
  };
}

export function normalizeInstalledApps(payload) {
  const source = Array.isArray(payload) ? payload : payload?.apps;
  if (!Array.isArray(source)) return [];

  const packageNames = new Set();
  const apps = [];
  source.forEach((entry) => {
    const packageName = typeof entry?.packageName === "string"
      ? entry.packageName.trim()
      : "";
    const uid = Number(entry?.uid);
    if (
      !packageName ||
      packageNames.has(packageName) ||
      !Number.isSafeInteger(uid) ||
      uid <= 0 ||
      uid > MAX_UID
    ) {
      return;
    }
    packageNames.add(packageName);
    const label = typeof entry?.label === "string" && entry.label.trim()
      ? entry.label.trim()
      : packageName;
    apps.push({
      packageName,
      label,
      uid,
      isSystem: entry?.isSystem === true,
    });
  });
  return apps.sort((first, second) =>
    Number(first.isSystem) - Number(second.isSystem) ||
    first.packageName.localeCompare(second.packageName, undefined, { sensitivity: "base" }),
  );
}

export function filterInstalledApps(apps, query) {
  const normalizedQuery = String(query ?? "").trim().toLowerCase();
  if (!normalizedQuery) return Array.isArray(apps) ? [...apps] : [];
  return (Array.isArray(apps) ? apps : []).filter((app) =>
    app.packageName.toLowerCase().includes(normalizedQuery) ||
    app.label.toLowerCase().includes(normalizedQuery),
  );
}

export function selectedPackageUid(apps, packageName) {
  const selectedPackage = String(packageName ?? "").trim();
  const app = (Array.isArray(apps) ? apps : []).find(
    (candidate) => candidate.packageName === selectedPackage,
  );
  if (!app) throw new Error("請先選擇目標 App 包名");
  return app.uid;
}

export function createObserveCommand({ syscallNr, scope, targetId, phase }) {
  const nr = parseInteger(syscallNr, "Syscall編號");
  if (nr < 0 || nr > 459) throw new Error("Syscall編號必須介於 0 至 459");
  const normalizedScope = String(scope ?? "").toLowerCase();
  if (!SCOPES.has(normalizedScope)) throw new Error("無效的範圍");
  const normalizedPhase = String(phase ?? "").toLowerCase();
  if (!PHASES.has(normalizedPhase)) throw new Error("無效的觀察階段");
  if ((nr === 93 || nr === 94) && normalizedPhase !== "enter") {
    throw new Error("exit 與 exit_group 僅支援進入階段觀察");
  }
  const target = parseInteger(targetId, "目標 ID");
  if (target <= 0) {
    throw new Error(normalizedScope === "uid" ? "UID 必須為正整數" : "目標 ID 必須為正整數");
  }
  const maxTarget = normalizedScope === "uid" ? MAX_UID : MAX_PROCESS_ID;
  if (target > maxTarget) {
    throw new Error(
      normalizedScope === "uid"
        ? `UID 必須介於 1 至 ${MAX_UID}`
        : `目標 ID 必須介於 1 至 ${MAX_PROCESS_ID}`,
    );
  }
  return {
    type: "observe",
    syscallNr: nr,
    scope: normalizedScope,
    targetId: target,
    phase: normalizedPhase,
  };
}

export function createUnobserveCommand(rule) {
  return {
    type: "unobserve",
    syscallNr: Number(rule.syscallNr),
    scope: String(rule.scope),
    targetId: Number(rule.targetId),
  };
}

function parseInteger(value, label) {
  const normalized = typeof value === "number" ? value : String(value).trim();
  if (normalized === "") throw new Error(`${label}必須為整數`);
  const parsed = typeof normalized === "number" ? normalized : Number(normalized);
  if (!Number.isSafeInteger(parsed)) throw new Error(`${label}必須為整數`);
  return parsed;
}

function scopeLabel(scope) {
  const normalizedScope = String(scope ?? "").toLowerCase();
  return SCOPE_LABELS[normalizedScope] ?? String(scope ?? "-");
}

function targetLabel(scope) {
  const normalizedScope = String(scope ?? "").toLowerCase();
  return TARGET_LABELS[normalizedScope] ?? "目標 ID";
}

function phaseLabel(phase) {
  const normalizedPhase = String(phase ?? "").toLowerCase();
  return PHASE_LABELS[normalizedPhase] ?? String(phase ?? "-");
}

export function formatReturn(value) {
  if (typeof value !== "string" || !value.startsWith("0x")) return value ?? "-";
  try {
    const raw = BigInt(value);
    const signed = raw >= (1n << 63n) ? raw - (1n << 64n) : raw;
    if (signed < 0n && signed >= -4095n) return `${value} (${signed})`;
    return value;
  } catch {
    return value;
  }
}

export function formatCapture(capture) {
  const kind = typeof capture?.kind === "string" && capture.kind
    ? capture.kind
    : "capture";
  const schema = typeof capture?.schema === "string" && capture.schema !== "none"
    ? capture.schema
    : kind;
  const phase = capture?.phase === "exit" ? "exit" : "enter";
  const status = typeof capture?.status === "string" && capture.status
    ? capture.status
    : "ok";
  const capturedLength = Number.isSafeInteger(capture?.capturedLength)
    ? capture.capturedLength
    : 0;
  const originalLength = Number.isSafeInteger(capture?.originalLength)
    ? capture.originalLength
    : capturedLength;
  const value = typeof capture?.value === "string"
    ? capture.value
    : (status === "ok" ? "<empty>" : `<${status}>`);
  const address = typeof capture?.address === "string" ? capture.address : "-";
  return {
    label: `${phase} | ${schema}`,
    value,
    meta: `${capturedLength}/${originalLength} B | ${status} | ${address}`,
  };
}

function createElement(documentRef, tag, className, text) {
  const node = documentRef.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function setupBrowserClient(documentRef, windowRef) {
  const elements = {
    connection: documentRef.getElementById("connection"),
    ruleForm: documentRef.getElementById("rule-form"),
    syscallNr: documentRef.getElementById("syscall-nr"),
    scope: documentRef.getElementById("scope"),
    numericTargetField: documentRef.getElementById("numeric-target-field"),
    packageTargetField: documentRef.getElementById("package-target-field"),
    targetLabel: documentRef.getElementById("target-label"),
    targetId: documentRef.getElementById("target-id"),
    packageCombobox: documentRef.getElementById("package-combobox"),
    packageSearch: documentRef.getElementById("package-search"),
    packageOptions: documentRef.getElementById("package-options"),
    packageStatus: documentRef.getElementById("package-status"),
    reloadApps: documentRef.getElementById("reload-apps"),
    ruleList: documentRef.getElementById("rule-list"),
    ruleCount: documentRef.getElementById("rule-count"),
    eventList: documentRef.getElementById("event-list"),
    eventCount: documentRef.getElementById("event-count"),
    clearEvents: documentRef.getElementById("clear-events"),
    listenerStatus: documentRef.getElementById("listener-status"),
    restartListener: documentRef.getElementById("restart-listener"),
    commandStatus: documentRef.getElementById("command-status"),
  };
  let model = initialModel();
  let socket = null;
  let reconnectTimer = null;
  let pendingAckMessages = [];
  let installedApps = [];
  let appsLoadState = "idle";
  let appsError = null;
  let selectedPackageName = "";
  let packageMenuOpen = false;
  let activePackageIndex = -1;
  let visiblePackageApps = [];
  let packageSelectionInvalid = false;

  function render() {
    renderSyscallSelector();
    renderRules(documentRef, elements, model, sendCommand);
    renderEvents(documentRef, elements, model);
    renderListenerStatus(elements, model.listener);
  }

  function setConnection(state, label) {
    elements.connection.className = `connection connection--${state}`;
    elements.connection.textContent = label;
  }

  function showCommandStatus(text, error = false) {
    elements.commandStatus.textContent = text;
    elements.commandStatus.classList.toggle("command-status--error", error);
  }

  function sendCommand(command, successMessage = "操作完成") {
    if (!socket || socket.readyState !== windowRef.WebSocket.OPEN) {
      showCommandStatus("連線尚未就緒", true);
      return;
    }
    pendingAckMessages.push(successMessage);
    socket.send(JSON.stringify(command));
  }

  function renderSyscallSelector() {
    const catalog = model.supportedSyscalls;
    const signature = catalog.map((entry) => `${entry.nr}:${entry.name}`).join("|");
    if (elements.syscallNr.dataset.catalog === signature) return;

    const currentValue = elements.syscallNr.value;
    const placeholder = createElement(
      documentRef,
      "option",
      "",
      catalog.length > 0 ? "選擇 Syscall" : "尚無可用的 Syscall",
    );
    placeholder.value = "";
    placeholder.disabled = true;
    elements.syscallNr.replaceChildren(placeholder);
    catalog.forEach((entry) => {
      const option = createElement(documentRef, "option", "", `${entry.nr} · ${entry.name}`);
      option.value = String(entry.nr);
      elements.syscallNr.append(option);
    });
    elements.syscallNr.disabled = catalog.length === 0;
    elements.syscallNr.value = catalog.some((entry) => String(entry.nr) === currentValue)
      ? currentValue
      : "";
    elements.syscallNr.dataset.catalog = signature;
  }

  function setPackageStatus(message, state = "normal") {
    elements.packageStatus.textContent = message;
    elements.packageStatus.classList.toggle("package-status--error", state === "error");
    elements.packageStatus.classList.toggle("package-status--selected", state === "selected");
  }

  function packageTypeLabel(app) {
    return app.isSystem ? "系統" : "使用者";
  }

  function packageSharedCount(app, packageCountByUid) {
    return packageCountByUid.get(app.uid) || 0;
  }

  function packageMatches() {
    const committedValue = selectedPackageName &&
      elements.packageSearch.value.trim() === selectedPackageName;
    return filterInstalledApps(installedApps, committedValue ? "" : elements.packageSearch.value);
  }

  function syncPackageMenuState() {
    const isUid = elements.scope.value === "uid";
    const canOpen = isUid && appsLoadState === "ready";
    const open = canOpen && packageMenuOpen;
    elements.packageCombobox.classList.toggle("package-combobox--open", open);
    elements.packageCombobox.classList.toggle(
      "package-combobox--invalid",
      canOpen && packageSelectionInvalid,
    );
    elements.packageSearch.setAttribute("aria-expanded", String(open));
    elements.packageSearch.setAttribute("aria-busy", appsLoadState === "loading" ? "true" : "false");
    elements.packageSearch.setAttribute("aria-invalid", String(canOpen && packageSelectionInvalid));
    elements.packageOptions.hidden = !open;
    if (open && activePackageIndex >= 0 && activePackageIndex < visiblePackageApps.length) {
      elements.packageSearch.setAttribute(
        "aria-activedescendant",
        `package-option-${activePackageIndex}`,
      );
      const activeOption = elements.packageOptions.querySelector(
        `#package-option-${activePackageIndex}`,
      );
      activeOption?.scrollIntoView?.({ block: "nearest" });
    } else {
      elements.packageSearch.removeAttribute("aria-activedescendant");
    }
  }

  function renderPackageOption(app, index, packageCountByUid) {
    const option = createElement(documentRef, "div", "package-option");
    const sharedCount = packageSharedCount(app, packageCountByUid);
    const sharedText = sharedCount > 1 ? `共享 UID（${sharedCount} 個包名）` : "";
    const typeLabel = packageTypeLabel(app);
    const typeClass = app.isSystem ? "package-kind--system" : "package-kind--user";
    const isSelected = app.packageName === selectedPackageName;

    option.id = `package-option-${index}`;
    option.setAttribute("role", "option");
    option.setAttribute("aria-selected", String(isSelected));
    option.setAttribute(
      "aria-label",
      `${app.packageName}，${app.label}，UID ${app.uid}，${typeLabel}${sharedText ? `，${sharedText}` : ""}`,
    );
    option.dataset.packageName = app.packageName;
    if (index === activePackageIndex) option.classList.add("package-option--active");

    const primary = createElement(documentRef, "div", "package-option-primary");
    const packageName = createElement(documentRef, "span", "package-option-name", app.packageName);
    packageName.title = app.packageName;
    primary.append(packageName);
    primary.append(createElement(documentRef, "span", `package-kind ${typeClass}`, typeLabel));
    option.append(primary);

    const meta = createElement(documentRef, "div", "package-option-meta");
    meta.append(createElement(documentRef, "span", "package-option-label", app.label));
    meta.append(createElement(documentRef, "span", "package-option-uid", `UID ${app.uid}`));
    if (sharedText) meta.append(createElement(documentRef, "span", "package-option-shared", sharedText));
    option.append(meta);
    return option;
  }

  function renderPackageGroup(label, apps, packageCountByUid, startIndex) {
    if (apps.length === 0) return null;
    const group = createElement(documentRef, "div", "package-group");
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", `${label}（${apps.length}）`);
    const heading = createElement(documentRef, "div", "package-group-label");
    heading.append(createElement(documentRef, "span", "", label));
    heading.append(createElement(documentRef, "span", "", String(apps.length)));
    group.append(heading);
    apps.forEach((app, offset) => {
      group.append(renderPackageOption(app, startIndex + offset, packageCountByUid));
    });
    return group;
  }

  function renderPackageSelector() {
    const isUid = elements.scope.value === "uid";
    const isReady = isUid && appsLoadState === "ready";
    elements.packageSearch.disabled = !isReady;
    elements.packageSearch.required = isReady;
    elements.reloadApps.hidden = !isUid || appsLoadState !== "error";

    if (!isUid) {
      visiblePackageApps = [];
      packageMenuOpen = false;
      activePackageIndex = -1;
      elements.packageOptions.replaceChildren();
      syncPackageMenuState();
      return;
    }
    if (appsLoadState === "idle" || appsLoadState === "loading") {
      visiblePackageApps = [];
      packageMenuOpen = false;
      activePackageIndex = -1;
      elements.packageOptions.replaceChildren();
      setPackageStatus("正在讀取已安裝套件...");
      syncPackageMenuState();
      return;
    }
    if (appsLoadState === "error") {
      visiblePackageApps = [];
      packageMenuOpen = false;
      activePackageIndex = -1;
      elements.packageOptions.replaceChildren();
      setPackageStatus(appsError || "無法讀取已安裝套件", "error");
      syncPackageMenuState();
      return;
    }

    const matches = packageMatches();
    visiblePackageApps = matches;
    const packageCountByUid = new Map();
    installedApps.forEach((app) => {
      packageCountByUid.set(app.uid, (packageCountByUid.get(app.uid) || 0) + 1);
    });
    const selectedIndex = matches.findIndex((app) => app.packageName === selectedPackageName);
    if (selectedIndex >= 0 && elements.packageSearch.value.trim() === selectedPackageName) {
      activePackageIndex = selectedIndex;
    } else if (activePackageIndex >= matches.length) {
      activePackageIndex = matches.length > 0 ? 0 : -1;
    }

    elements.packageOptions.replaceChildren();
    const userApps = matches.filter((app) => !app.isSystem);
    const systemApps = matches.filter((app) => app.isSystem);
    const userGroup = renderPackageGroup("使用者 App", userApps, packageCountByUid, 0);
    const systemGroup = renderPackageGroup("系統 App", systemApps, packageCountByUid, userApps.length);
    if (userGroup) elements.packageOptions.append(userGroup);
    if (systemGroup) elements.packageOptions.append(systemGroup);
    if (matches.length === 0) {
      const empty = createElement(
        documentRef,
        "p",
        "package-options-empty",
        installedApps.length === 0
          ? "沒有可觀察的已安裝套件"
          : "找不到符合的包名，請縮短搜尋條件",
      );
      empty.setAttribute("role", "status");
      elements.packageOptions.append(empty);
    }

    const selected = installedApps.find((app) => app.packageName === selectedPackageName);
    if (packageSelectionInvalid) {
      setPackageStatus("請從清單中選擇目標 App 包名", "error");
    } else if (selected) {
      const sharedCount = packageSharedCount(selected, packageCountByUid);
      const shared = sharedCount > 1 ? ` · ${sharedCount} 個包名共享此 UID` : "";
      setPackageStatus(`${packageTypeLabel(selected)} · ${selected.label} · UID ${selected.uid}${shared}`, "selected");
    } else if (matches.length === 0) {
      setPackageStatus(
        installedApps.length === 0 ? "沒有可觀察的已安裝套件" : "找不到符合的包名",
      );
    } else {
      setPackageStatus(`顯示 ${matches.length} / ${installedApps.length} 個套件`);
    }
    syncPackageMenuState();
  }

  function openPackageMenu() {
    if (elements.scope.value !== "uid" || appsLoadState !== "ready") return;
    if (packageMenuOpen) return;
    packageMenuOpen = true;
    renderPackageSelector();
  }

  function closePackageMenu() {
    if (!packageMenuOpen) return;
    packageMenuOpen = false;
    activePackageIndex = -1;
    renderPackageSelector();
  }

  function selectPackageAt(index) {
    const app = visiblePackageApps[index];
    if (!app) return;
    selectedPackageName = app.packageName;
    elements.packageSearch.value = app.packageName;
    packageSelectionInvalid = false;
    activePackageIndex = index;
    packageMenuOpen = false;
    renderPackageSelector();
  }

  function movePackageActive(direction) {
    if (visiblePackageApps.length === 0) return;
    if (!packageMenuOpen) packageMenuOpen = true;
    if (activePackageIndex < 0) {
      activePackageIndex = direction > 0 ? 0 : visiblePackageApps.length - 1;
    } else {
      activePackageIndex = (activePackageIndex + direction + visiblePackageApps.length) % visiblePackageApps.length;
    }
    renderPackageSelector();
  }

  function handlePackageKeydown(event) {
    if (elements.packageSearch.disabled) return;
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        movePackageActive(1);
        break;
      case "ArrowUp":
        event.preventDefault();
        movePackageActive(-1);
        break;
      case "Home":
        if (!packageMenuOpen) return;
        event.preventDefault();
        activePackageIndex = visiblePackageApps.length > 0 ? 0 : -1;
        renderPackageSelector();
        break;
      case "End":
        if (!packageMenuOpen) return;
        event.preventDefault();
        activePackageIndex = visiblePackageApps.length - 1;
        renderPackageSelector();
        break;
      case "Enter":
        if (packageMenuOpen && activePackageIndex >= 0) {
          event.preventDefault();
          selectPackageAt(activePackageIndex);
        }
        break;
      case "Escape":
        if (packageMenuOpen) {
          event.preventDefault();
          closePackageMenu();
        }
        break;
      case "Tab":
        closePackageMenu();
        break;
      default:
        break;
    }
  }

  async function loadInstalledApps() {
    if (appsLoadState === "loading") return;
    appsLoadState = "loading";
    appsError = null;
    renderPackageSelector();

    try {
      const token = new windowRef.URLSearchParams(windowRef.location.search).get("token");
      if (!token) throw new Error("缺少存取權杖");
      const response = await windowRef.fetch(
        `/api/apps?token=${encodeURIComponent(token)}`,
        { cache: "no-store", headers: { Accept: "application/json" } },
      );
      const payload = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(payload?.error || `無法讀取已安裝套件（HTTP ${response.status}）`);
      }
      installedApps = normalizeInstalledApps(payload);
      if (!installedApps.some((app) => app.packageName === selectedPackageName)) {
        selectedPackageName = "";
        elements.packageSearch.value = "";
      }
      packageSelectionInvalid = false;
      appsLoadState = "ready";
    } catch (error) {
      appsLoadState = "error";
      appsError = error instanceof Error ? error.message : "無法讀取已安裝套件";
    }
    renderPackageSelector();
  }

  function updateScope() {
    const isUid = elements.scope.value === "uid";
    if (!isUid) {
      packageMenuOpen = false;
      activePackageIndex = -1;
      packageSelectionInvalid = false;
    }
    elements.numericTargetField.hidden = isUid;
    elements.packageTargetField.hidden = !isUid;
    elements.targetId.disabled = isUid;
    elements.targetId.required = !isUid;
    elements.targetId.min = "1";
    elements.targetId.max = String(MAX_PROCESS_ID);
    elements.targetLabel.textContent = targetLabel(elements.scope.value);
    renderPackageSelector();
    if (isUid && appsLoadState === "idle") loadInstalledApps();
  }

  function scheduleReconnect() {
    if (reconnectTimer !== null) return;
    reconnectTimer = windowRef.setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, RECONNECT_DELAY_MS);
  }

  function connect() {
    if (socket && (socket.readyState === windowRef.WebSocket.OPEN || socket.readyState === windowRef.WebSocket.CONNECTING)) {
      return;
    }
    const token = new windowRef.URLSearchParams(windowRef.location.search).get("token");
    if (!token) {
      setConnection("offline", "缺少存取權杖");
      return;
    }
    const scheme = windowRef.location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${scheme}//${windowRef.location.host}/ws?token=${encodeURIComponent(token)}`;
    setConnection("pending", "正在連線");
    socket = new windowRef.WebSocket(url);
    socket.addEventListener("open", () => setConnection("connected", "已連線"));
    socket.addEventListener("message", (event) => {
      let message;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      if (message.type === "ack") {
        const successMessage = pendingAckMessages.shift() || "操作完成";
        showCommandStatus(message.ok ? successMessage : (message.error || "命令執行失敗"), !message.ok);
        return;
      }
      model = reduceServerMessage(model, message);
      render();
    });
    socket.addEventListener("close", () => {
      socket = null;
      pendingAckMessages = [];
      setConnection("offline", "連線已中斷");
      scheduleReconnect();
    });
    socket.addEventListener("error", () => {
      setConnection("offline", "連線錯誤");
    });
  }

  elements.scope.addEventListener("change", updateScope);
  elements.packageSearch.addEventListener("focus", openPackageMenu);
  elements.packageSearch.addEventListener("click", openPackageMenu);
  elements.packageSearch.addEventListener("input", () => {
    if (elements.packageSearch.value.trim() !== selectedPackageName) {
      selectedPackageName = "";
    }
    packageSelectionInvalid = false;
    packageMenuOpen = true;
    activePackageIndex = 0;
    renderPackageSelector();
  });
  elements.packageSearch.addEventListener("keydown", handlePackageKeydown);
  elements.packageOptions.addEventListener("pointerdown", (event) => {
    const option = event.target.closest?.("[role=option]");
    if (option) event.preventDefault();
  });
  elements.packageOptions.addEventListener("click", (event) => {
    const option = event.target.closest?.("[role=option]");
    if (!option) return;
    const index = Number(option.id.replace("package-option-", ""));
    if (Number.isInteger(index)) selectPackageAt(index);
  });
  documentRef.addEventListener("pointerdown", (event) => {
    if (!elements.packageCombobox.contains(event.target)) closePackageMenu();
  });
  elements.reloadApps.addEventListener("click", loadInstalledApps);
  elements.ruleForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const phase = elements.ruleForm.querySelector("input[name=phase]:checked")?.value;
    try {
      const targetId = elements.scope.value === "uid"
        ? selectedPackageUid(installedApps, selectedPackageName)
        : elements.targetId.value;
      const command = createObserveCommand({
        syscallNr: elements.syscallNr.value,
        scope: elements.scope.value,
        targetId,
        phase,
      });
      sendCommand(command, "規則已新增");
    } catch (error) {
      const message = error instanceof Error ? error.message : "無效的規則";
      packageSelectionInvalid = elements.scope.value === "uid" &&
        !installedApps.some((app) => app.packageName === selectedPackageName);
      if (packageSelectionInvalid) {
        packageMenuOpen = true;
        renderPackageSelector();
        elements.packageSearch.focus();
      }
      showCommandStatus(message, true);
    }
  });
  elements.clearEvents.addEventListener("click", () => {
    model = clearEventsModel(model);
    render();
  });
  elements.restartListener.addEventListener("click", () => {
    sendCommand({ type: "restartListener" }, "已請求重新啟動讀取器");
  });

  render();
  updateScope();
  connect();
}

function renderRules(documentRef, elements, model, sendCommand) {
  const rules = Array.isArray(model.rules) ? model.rules : [];
  elements.ruleCount.textContent = String(rules.length);
  elements.ruleList.replaceChildren();
  if (rules.length === 0) {
    elements.ruleList.append(createElement(documentRef, "p", "empty-state", "目前沒有作用中規則"));
    return;
  }
  rules.forEach((rule) => {
    const row = createElement(documentRef, "article", "rule-row");
    const summary = createElement(documentRef, "div", "rule-summary");
    summary.append(
      createElement(documentRef, "span", "rule-name", `${rule.syscallNr} ${rule.name || syscallName(rule.syscallNr)}`),
      createElement(documentRef, "span", "rule-scope", scopeLabel(rule.scope)),
    );
    const target = String(rule.scope).toLowerCase() === "global"
      ? scopeLabel(rule.scope)
      : `${scopeLabel(rule.scope)} ${rule.targetId}`;
    row.append(
      summary,
      createElement(documentRef, "div", "rule-target", `${target} | ${phaseLabel(rule.phase)}`),
    );
    const remove = createElement(documentRef, "button", "button button--danger", "移除");
    remove.type = "button";
    remove.addEventListener("click", () => sendCommand(createUnobserveCommand(rule), "規則已移除"));
    row.append(remove);
    elements.ruleList.append(row);
  });
}

function renderListenerStatus(elements, listener) {
  const state = listener?.state || "starting";
  const labels = {
    stopped: "讀取器已停止",
    starting: "讀取器正在啟動",
    running: "讀取器執行中",
    failed: "讀取器執行失敗",
  };
  elements.listenerStatus.className = `listener-status listener-status--${state}`;
  elements.listenerStatus.textContent = labels[state] || labels.starting;
  elements.listenerStatus.title = listener?.error || "";
  elements.restartListener.disabled = state === "starting";
}

// Per-list render cache: displayId -> { node, event }. Keeping the DOM node
// and the event object it was built from lets a live stream reuse rows instead
// of rebuilding every <details> subtree on each message, which also preserves
// expanded rows and scroll position.
const eventRenderCache = new WeakMap();

function renderEvents(documentRef, elements, model) {
  const events = Array.isArray(model.events) ? model.events : [];
  const list = elements.eventList;
  elements.eventCount.textContent = String(events.length);

  if (events.length === 0) {
    if (eventRenderCache.has(list)) eventRenderCache.delete(list);
    list.replaceChildren(createElement(documentRef, "p", "empty-state", "尚未收到呼叫紀錄"));
    return;
  }

  let cache = eventRenderCache.get(list);
  if (!cache) {
    cache = new Map();
    eventRenderCache.set(list, cache);
  }

  // Build the desired node for each event, reusing the cached node whenever the
  // event object is unchanged (===). A changed object (e.g. an enter promoted
  // to complete) is rebuilt but keeps its open state.
  const nextCache = new Map();
  const desired = [];
  events.forEach((event) => {
    const key = event.displayId;
    const cached = cache.get(key);
    let node;
    if (cached && cached.event === event) {
      node = cached.node;
    } else {
      node = renderEvent(documentRef, event);
      if (cached && cached.node.open) node.open = true;
    }
    nextCache.set(key, { node, event });
    desired.push(node);
  });

  reconcileChildren(list, desired);
  eventRenderCache.set(list, nextCache);
}

// Reorder `parent`'s children to match `desired` with minimal DOM moves. Nodes
// already in the right relative position are left untouched; a prepend of new
// events costs one insert per new node rather than a full rebuild.
function reconcileChildren(parent, desired) {
  const wanted = new Set(desired);
  for (const child of Array.from(parent.childNodes)) {
    if (!wanted.has(child)) parent.removeChild(child);
  }
  let cursor = parent.firstChild;
  for (const node of desired) {
    if (node === cursor) {
      cursor = cursor.nextSibling;
    } else {
      parent.insertBefore(node, cursor);
    }
  }
}

function renderEvent(documentRef, event) {
  const phaseKind = event.afterDropped ? "dropped" : String(event.phase ?? "");
  const details = createElement(documentRef, "details", `event-row phase-${phaseKind}`);
  const summary = createElement(documentRef, "summary");
  const eventSummary = createElement(documentRef, "div", "event-summary");
  const titleBlock = createElement(documentRef, "div", "event-title-block");
  titleBlock.append(
    createElement(documentRef, "div", "event-title", `${event.syscallNr} ${event.name || syscallName(event.syscallNr)}`),
    renderEventMeta(documentRef, event),
  );
  const capturePreview = (Array.isArray(event.captures) ? event.captures : [])
    .find((capture) => typeof capture?.value === "string");
  if (capturePreview) {
    const formatted = formatCapture(capturePreview);
    titleBlock.append(createElement(
      documentRef,
      "div",
      "event-capture-preview",
      `x${capturePreview.argIndex} = ${formatted.value}`,
    ));
  }
  const phase = createElement(
    documentRef,
    "span",
    `event-phase event-phase--${phaseKind}`,
    event.afterDropped ? "遺失" : phaseLabel(event.phase),
  );
  eventSummary.append(titleBlock, phase);
  summary.append(eventSummary);
  details.append(summary, renderEventDetails(documentRef, event));
  return details;
}

function renderEventMeta(documentRef, event) {
  const meta = createElement(documentRef, "div", "event-meta");
  const entries = [
    `#${event.displayId}`,
    `Thread（TID）${event.tid}`,
    `Process（TGID）${event.tgid}`,
    event.comm || "?",
    `呼叫 ID ${event.callId || "-"}`,
  ];
  entries.forEach((text) => meta.append(createElement(documentRef, "span", "", text)));
  return meta;
}

function renderEventDetails(documentRef, event) {
  const body = createElement(documentRef, "div", "event-details");
  const argsSection = createElement(documentRef, "section");
  argsSection.append(createElement(documentRef, "h3", "", "參數"));
  const args = createElement(documentRef, "div", "event-args");
  const effectiveArgs = Array.isArray(event.args) ? event.args : [];
  const originalArgs = Array.isArray(event.argsBefore) ? event.argsBefore : [];
  const captures = Array.isArray(event.captures) ? event.captures : [];
  const count = Math.max(6, effectiveArgs.length, originalArgs.length);
  for (let index = 0; index < count; index += 1) {
    const before = originalArgs[index];
    const after = effectiveArgs[index];
    const changed = Boolean(before && after && before !== after);
    const argumentCaptures = captures
      .filter((capture) => Number(capture?.argIndex) === index);
    const classes = ["event-arg"];
    if (changed) classes.push("event-arg--changed");
    if (argumentCaptures.length > 0) classes.push("event-arg--captured");
    const arg = createElement(documentRef, "div", classes.join(" "));
    const value = changed ? `${before} -> ${after}` : (after || before || "-");
    arg.append(
      createElement(documentRef, "span", "event-arg-label", `x${index}`),
      createElement(documentRef, "span", "event-arg-value", value),
    );
    argumentCaptures.forEach((capture) => {
      const formatted = formatCapture(capture);
      const decoded = createElement(documentRef, "div", "event-arg-capture");
      decoded.append(
        createElement(documentRef, "span", "event-capture-kind", formatted.label),
        createElement(documentRef, "code", "event-capture-value", formatted.value),
        createElement(documentRef, "span", "event-capture-meta", formatted.meta),
      );
      arg.append(decoded);
    });
    args.append(arg);
  }
  argsSection.append(args);
  body.append(argsSection);

  if (event.return !== null && event.return !== undefined) {
    const returnSection = createElement(documentRef, "section");
    returnSection.append(createElement(documentRef, "h3", "", "返回值"));
    const returnGrid = createElement(documentRef, "div", "event-detail-grid");
    returnGrid.append(renderDetail(documentRef, "x0", formatReturn(event.return)));
    body.append(returnSection);
    returnSection.append(returnGrid);
  }

  const metaSection = createElement(documentRef, "section");
  metaSection.append(createElement(documentRef, "h3", "", "紀錄"));
  const metaGrid = createElement(documentRef, "div", "event-detail-grid");
  const target = String(event.scope).toLowerCase() === "global"
    ? scopeLabel(event.scope)
    : `${scopeLabel(event.scope || "tgid")} ${event.targetId}`;
  [
    ["序號", event.seq || "-"],
    ["遺失", event.dropped || "-"],
    ["轉送遺失", String(event.relayDropped || 0)],
    ["目標", target],
    ["旗標", String(event.eventFlags ?? 0)],
    ["呼叫 ID", event.callId || "-"],
    ["使用者識別碼", event.userCookie || "0x0"],
  ].forEach(([label, value]) => metaGrid.append(renderDetail(documentRef, label, value)));
  metaSection.append(metaGrid);
  body.append(metaSection);
  return body;
}

function renderDetail(documentRef, label, value) {
  const detail = createElement(documentRef, "div", "event-detail");
  detail.append(
    createElement(documentRef, "span", "event-detail-label", label),
    createElement(documentRef, "span", "event-detail-value", String(value)),
  );
  return detail;
}

if (typeof document !== "undefined" && typeof window !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => setupBrowserClient(document, window));
}
