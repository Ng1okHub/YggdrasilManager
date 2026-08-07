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
  [281, "execveat"], [291, "statx"], [435, "clone3"], [437, "openat2"],
]);

export function syscallName(syscallNr) {
  return SYSCALL_NAMES.get(Number(syscallNr)) ?? `syscall_${syscallNr}`;
}

export function initialModel() {
  return {
    rules: [],
    events: [],
    listener: { state: "starting", error: null },
    nextDisplayId: 1,
  };
}

export function reduceServerMessage(model, message) {
  if (message?.type === "state") {
    return {
      ...model,
      rules: Array.isArray(message.rules) ? [...message.rules] : [],
      listener: normalizeListener(message.listener, model.listener),
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
  const parsed = typeof value === "number" ? value : Number(String(value).trim());
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
    syscallName: documentRef.getElementById("syscall-name"),
    scope: documentRef.getElementById("scope"),
    targetField: documentRef.getElementById("target-field"),
    targetLabel: documentRef.getElementById("target-label"),
    targetId: documentRef.getElementById("target-id"),
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

  function render() {
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

  function updateScope() {
    const isUid = elements.scope.value === "uid";
    elements.targetId.min = "1";
    elements.targetId.max = String(isUid ? MAX_UID : MAX_PROCESS_ID);
    elements.targetLabel.textContent = targetLabel(elements.scope.value);
  }

  function updateSyscallName() {
    const value = elements.syscallNr.value;
    const nr = Number(value);
    elements.syscallName.textContent = Number.isInteger(nr) && nr >= 0 && nr <= 459
      ? `${nr}: ${syscallName(nr)}`
      : "";
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
  elements.syscallNr.addEventListener("input", updateSyscallName);
  elements.ruleForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const phase = elements.ruleForm.querySelector("input[name=phase]:checked")?.value;
    try {
      const command = createObserveCommand({
        syscallNr: elements.syscallNr.value,
        scope: elements.scope.value,
        targetId: elements.targetId.value,
        phase,
      });
      sendCommand(command, "規則已新增");
    } catch (error) {
      showCommandStatus(error instanceof Error ? error.message : "無效的規則", true);
    }
  });
  elements.clearEvents.addEventListener("click", () => {
    model = clearEventsModel(model);
    render();
  });
  elements.restartListener.addEventListener("click", () => {
    sendCommand({ type: "restartListener" }, "已請求重新啟動讀取器");
  });

  updateScope();
  updateSyscallName();
  render();
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
