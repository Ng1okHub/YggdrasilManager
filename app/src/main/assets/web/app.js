export const EVENT_LIMIT = 500;
export const RECONNECT_DELAY_MS = 3000;
export const DEFAULT_STACK_SIZE = 4096;

const MODES = new Set(["R", "W", "RW", "X"]);
const LENGTHS = new Set([1, 2, 4, 8]);
const BT_MODES = new Set(["fp", "dwarf"]);

export function initialModel() {
  return {
    points: [],
    savedConfigs: [],
    events: [],
    backtraceMode: "fp",
    nextDisplayId: 1,
  };
}

export function reduceServerMessage(model, message) {
  if (message?.type === "state") {
    return {
      ...model,
      points: Array.isArray(message.points) ? [...message.points] : [],
      savedConfigs: Array.isArray(message.savedConfigs) ? [...message.savedConfigs] : [],
      backtraceMode: normalizeBacktraceMode(message.backtraceMode ?? model.backtraceMode),
    };
  }
  if (message?.type === "event") {
    const displayId = Number(model.nextDisplayId) > 0 ? Number(model.nextDisplayId) : 1;
    return {
      ...model,
      nextDisplayId: displayId + 1,
      events: [{ ...message, displayId }, ...model.events].slice(0, EVENT_LIMIT),
    };
  }
  return model;
}

export function clearEventsModel(model) {
  return {
    ...model,
    events: [],
    nextDisplayId: 1,
  };
}

export function buildObserveCommand(values) {
  return {
    type: "observe",
    ...buildProcessTarget(values),
    ...buildObserveDefinition(values),
  };
}

export function buildSaveConfigCommand(values, id = null) {
  const process = buildProcessTarget(values, true);
  const configId = id ?? values.id;
  return {
    type: "saveConfig",
    ...(configId ? { id: normalizeConfigId(configId) } : {}),
    ...process,
    ...buildObserveDefinition(values),
    note: String(values.note ?? "").trim(),
  };
}

export function buildApplyConfigCommand(id) {
  return { type: "applyConfig", id: normalizeConfigId(id) };
}

export function buildDeleteConfigCommand(id) {
  return { type: "deleteConfig", id: normalizeConfigId(id) };
}

function buildObserveDefinition(values) {
  const mode = String(values.mode ?? "").trim().toUpperCase();
  if (!MODES.has(mode)) {
    throw new Error("Mode must be R, W, RW, or X");
  }

  const requestedLength = positiveInteger(values.len, "Length");
  if (!LENGTHS.has(requestedLength)) {
    throw new Error("Length must be 1, 2, 4, or 8");
  }

  const stackSize = values.stackSize === undefined || values.stackSize === ""
    ? DEFAULT_STACK_SIZE
    : nonNegativeInteger(values.stackSize, "Stack size");
  if (stackSize > 65536) {
    throw new Error("Stack size must be between 0 and 65536");
  }

  const targetKind = String(values.targetKind ?? "abs").trim().toLowerCase();
  const base = {
    mode,
    len: mode === "X" ? 4 : requestedLength,
    singleThread: Boolean(values.singleThread),
    stackSize,
  };

  if (targetKind === "lib") {
    const lib = String(values.lib ?? "").trim();
    if (!lib) throw new Error("Library name is required");
    return {
      ...base,
      lib,
      offset: normalizeOffset(values.offset),
    };
  }

  return {
    ...base,
    addr: normalizeAddress(values.addr),
  };
}

function buildProcessTarget(values, requirePackage = false) {
  const processKind = String(values.processKind ?? "package").trim().toLowerCase();
  if (processKind === "package") {
    return { packageName: normalizePackageName(values.packageName) };
  }
  if (processKind === "pid") {
    if (requirePackage) {
      throw new Error("Saved configurations require an app package name");
    }
    return { pid: positiveInteger(values.pid, "PID") };
  }
  throw new Error("Target process must be a package name or PID");
}

export function buildUnobserveCommand(values) {
  return {
    type: "unobserve",
    pid: positiveInteger(values.pid, "PID"),
    addr: normalizeAddress(values.addr),
  };
}

export function buildConfigCommand(values) {
  return {
    type: "config",
    backtraceMode: normalizeBacktraceMode(values.backtraceMode),
  };
}

export function normalizeBacktraceMode(value) {
  const mode = String(value ?? "fp").trim().toLowerCase();
  if (!BT_MODES.has(mode)) {
    throw new Error("Backtrace mode must be fp or dwarf");
  }
  return mode;
}

function normalizePackageName(value) {
  const packageName = String(value ?? "").trim();
  if (!/^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(packageName)) {
    throw new Error("App package name is invalid");
  }
  return packageName;
}

function normalizeConfigId(value) {
  const id = String(value ?? "").trim();
  if (!id || id.length > 128) throw new Error("Configuration id is invalid");
  return id;
}

function normalizeAddress(value) {
  const match = String(value ?? "").trim().match(/^(?:0x)?([0-9a-f]+)$/i);
  if (!match || match[1].length > 16) {
    throw new Error("Address must be a 64-bit hexadecimal value");
  }
  const digits = match[1].toLowerCase().replace(/^0+(?=[0-9a-f])/, "");
  return `0x${digits}`;
}

function normalizeOffset(value) {
  const text = String(value ?? "").trim();
  if (!text) throw new Error("Offset is required");
  if (/^(?:0x)?[0-9a-f]+$/i.test(text)) {
    return normalizeAddress(text);
  }
  const number = Number(text);
  if (!Number.isInteger(number) || number < 0) {
    throw new Error("Offset must be a non-negative integer or hex value");
  }
  return `0x${number.toString(16)}`;
}

function positiveInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) {
    throw new Error(`${label} must be a positive integer`);
  }
  return number;
}

function nonNegativeInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0) {
    throw new Error(`${label} must be a non-negative integer`);
  }
  return number;
}

const eventCardControllers = new WeakMap();

export function createEventCard(
  document,
  event,
  fallbackBacktraceMode = "fp",
  initiallyOpen = false,
) {
  const details = document.createElement("details");
  details.className = "event-card";

  const summary = document.createElement("summary");
  summary.className = "event-summary";

  const left = document.createElement("div");
  const identity = document.createElement("div");
  identity.className = "event-identity";
  identity.textContent = `#${event.displayId ?? event.seq}  ${event.comm ?? "unknown"}`;
  const context = document.createElement("div");
  context.className = "event-context";
  context.textContent = `tid=${event.tid}  tgid=${event.tgid}  kseq=${event.seq}`;
  left.append(identity, context);

  const meta = document.createElement("div");
  meta.className = "event-meta";
  const btBadge = document.createElement("span");
  btBadge.className = "badge";
  btBadge.textContent = `bt:${event.backtraceMode || fallbackBacktraceMode}`;
  meta.append(btBadge);
  if (Number(event.dropped) > 0) {
    const dropped = document.createElement("span");
    dropped.className = "badge warn";
    dropped.textContent = `dropped ${event.dropped}`;
    meta.append(dropped);
  }
  summary.append(left, meta);
  details.append(summary);

  let body = null;
  const syncBody = () => {
    if (details.open && body === null) {
      body = createEventBody(document, event);
      details.append(body);
    } else if (!details.open && body !== null) {
      body.remove();
      body = null;
    }
  };
  eventCardControllers.set(details, { syncBody });
  details.addEventListener("toggle", syncBody);
  summary.addEventListener("click", () => {
    details.dataset.autoOpened = "false";
  });
  setEventCardOpen(details, Boolean(initiallyOpen), Boolean(initiallyOpen));
  return details;
}

export function prependEventCard(document, container, event, fallbackBacktraceMode = "fp") {
  const emptyState = container.firstElementChild;
  if (emptyState?.classList?.contains("empty-state")) emptyState.remove();

  const previousNewest = container.firstElementChild;
  if (
    previousNewest?.classList?.contains("event-card") &&
    previousNewest.dataset.autoOpened === "true"
  ) {
    setEventCardOpen(previousNewest, false, false);
  }

  const card = createEventCard(document, event, fallbackBacktraceMode, true);
  container.prepend(card);
  while (container.childElementCount > EVENT_LIMIT) {
    container.lastElementChild?.remove();
  }
  return card;
}

function setEventCardOpen(card, open, autoOpened) {
  card.dataset.autoOpened = autoOpened ? "true" : "false";
  card.open = open;
  eventCardControllers.get(card)?.syncBody();
}

function createEventBody(document, event) {
  const body = document.createElement("div");
  body.className = "event-body";

  const btHeader = document.createElement("div");
  btHeader.className = "section-label";
  const btTitle = document.createElement("span");
  btTitle.textContent = "Backtrace";
  btHeader.append(btTitle);
  body.append(btHeader);

  const frames = Array.isArray(event.backtrace) ? event.backtrace : [];
  if (event.backtraceNote) {
    const note = document.createElement("div");
    note.className = "note";
    note.textContent = event.backtraceNote;
    body.append(note);
  }

  const bt = document.createElement("div");
  bt.className = "backtrace";
  if (frames.length === 0) {
    bt.append(createEmptyState(document, "No frames"));
  } else {
    frames.forEach((frame) => {
      const row = document.createElement("div");
      row.className = "bt-frame";
      const idx = document.createElement("div");
      idx.className = "bt-index";
      idx.textContent = `#${frame.index ?? 0}`;
      const content = document.createElement("div");
      const label = document.createElement("div");
      label.className = "bt-label";
      label.textContent = frame.label || frame.pc || "-";
      const pc = document.createElement("div");
      pc.className = "bt-pc";
      pc.textContent = frame.pc || "";
      content.append(label);
      if (frame.pc && frame.pc !== frame.label) content.append(pc);
      row.append(idx, content);
      bt.append(row);
    });
  }
  body.append(bt);

  const kv = document.createElement("div");
  kv.className = "kv-grid";
  kv.append(
    createValueRow(document, "Watched", event.watchedAddr),
    createValueRow(document, "PC", event.pc),
    createValueRow(document, "SP", event.sp),
  );
  if (event.stack) {
    kv.append(
      createValueRow(
        document,
        "Stack",
        `${event.stack.copied ?? 0}/${event.stack.requested ?? 0} @ ${event.stack.addr ?? "-"}`,
      ),
    );
  }
  body.append(kv);

  const regsDetails = document.createElement("details");
  regsDetails.className = "regs-details";
  const regsSummary = document.createElement("summary");
  regsSummary.textContent = "Registers (secondary)";
  const registers = document.createElement("div");
  registers.className = "register-grid";
  const registerValues = Array.isArray(event.regs) ? event.regs : [];
  registerValues.forEach((value, index) => {
    registers.append(createValueRow(document, `x${index}`, value));
  });
  regsDetails.append(regsSummary, registers);
  body.append(regsDetails);
  return body;
}

function startBrowserClient(document, window) {
  const connectionState = document.getElementById("connection-state");
  const connectionStateText = document.getElementById("connection-state-text");
  const form = document.getElementById("observe-form");
  const formError = document.getElementById("form-error");
  const points = document.getElementById("points");
  const pointCount = document.getElementById("point-count");
  const savedConfigs = document.getElementById("saved-configs");
  const savedConfigCount = document.getElementById("saved-config-count");
  const events = document.getElementById("events");
  const eventCount = document.getElementById("event-count");
  const clearEvents = document.getElementById("clear-events");
  const modeGroup = document.getElementById("mode-group");
  const processKindGroup = document.getElementById("process-kind-group");
  const targetKindGroup = document.getElementById("target-kind-group");
  const btGroup = document.getElementById("bt-group");
  const btModeLabel = document.getElementById("bt-mode-label");
  const packageField = document.getElementById("package-field");
  const pidField = document.getElementById("pid-field");
  const packageInput = document.getElementById("packageName");
  const pidInput = document.getElementById("pid");
  const addrField = document.getElementById("addr-field");
  const libField = document.getElementById("lib-field");
  const offsetField = document.getElementById("offset-field");
  const saveConfigInput = document.getElementById("save-config");
  const configFields = document.getElementById("config-fields");
  const configNote = document.getElementById("config-note");
  const saveConfigButton = document.getElementById("save-config-command");
  const lengthInputs = [...document.querySelectorAll('input[name="len"]')];
  const btInputs = [...document.querySelectorAll('input[name="backtraceMode"]')];

  let model = initialModel();
  let socket = null;
  let reconnectTimer = null;
  let pendingBacktraceMode = null;
  let pendingObserveAfterConfig = null;
  let editingConfigId = null;

  function setConnectionState(state) {
    connectionState.dataset.state = state;
    const label = {
      connected: "Connected",
      connecting: "Connecting",
      disconnected: "Disconnected",
    }[state];
    if (connectionStateText) connectionStateText.textContent = label;
    else connectionState.textContent = label;
  }

  function setFormError(message = "") {
    formError.textContent = message;
    formError.hidden = message.length === 0;
  }

  function sendCommand(command) {
    if (!socket || socket.readyState !== window.WebSocket.OPEN) {
      setFormError("WebSocket is not connected");
      return false;
    }
    socket.send(JSON.stringify(command));
    return true;
  }

  function syncTargetKind() {
    const kind = new window.FormData(form).get("targetKind") || "abs";
    const isLib = kind === "lib";
    const absFields = document.getElementById("abs-target-fields");
    const libFields = document.getElementById("lib-target-fields");
    if (absFields) absFields.classList.toggle("is-hidden", isLib);
    if (libFields) libFields.classList.toggle("is-hidden", !isLib);
    // Fallback for older markup.
    if (addrField) addrField.hidden = isLib;
    if (libField) libField.hidden = !isLib;
    if (offsetField) offsetField.hidden = !isLib;
    form.addr.required = !isLib;
    form.addr.disabled = isLib;
    form.lib.required = isLib;
    form.lib.disabled = !isLib;
    form.offset.required = isLib;
    form.offset.disabled = !isLib;
    if (isLib) form.addr.value = "";
  }

  function syncProcessKind() {
    const kind = new window.FormData(form).get("processKind") || "package";
    const isPackage = kind === "package";
    packageField.classList.toggle("is-hidden", !isPackage);
    pidField.classList.toggle("is-hidden", isPackage);
    packageInput.required = isPackage;
    packageInput.disabled = !isPackage;
    pidInput.required = !isPackage;
    pidInput.disabled = isPackage;
  }

  function syncSaveConfig() {
    configFields.classList.toggle("is-hidden", !saveConfigInput.checked);
  }

  function syncModeLength() {
    const selectedMode = new window.FormData(form).get("mode");
    lengthInputs.forEach((input) => {
      input.disabled = selectedMode === "X" && input.value !== "4";
      if (selectedMode === "X" && input.value === "4") input.checked = true;
    });
  }

  function setRadioValue(name, value) {
    document.querySelectorAll(`input[name="${name}"]`).forEach((input) => {
      input.checked = input.value === value;
    });
  }

  function resetConfigEditing() {
    editingConfigId = null;
    saveConfigButton.textContent = "Save configuration";
  }

  function syncBacktraceModeUi() {
    const mode = model.backtraceMode || "fp";
    if (btModeLabel) btModeLabel.textContent = mode;
    btInputs.forEach((input) => {
      input.checked = input.value === mode;
    });
  }

  function renderPoints() {
    pointCount.textContent = String(model.points.length);
    const fragment = document.createDocumentFragment();

    if (model.points.length === 0) {
      fragment.append(createEmptyState(document, "No active observe points"));
    } else {
      model.points.forEach((point) => {
        const row = document.createElement("div");
        row.className = "point-row";

        const description = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = point.packageName
          ? `${point.packageName} (PID ${point.pid})`
          : `PID ${point.pid}`;
        const metadata = document.createElement("span");
        const display = point.display ||
          (point.lib && point.offset ? `${point.lib}+${point.offset}` : point.addr);
        metadata.textContent = `${display} · ${point.mode}/${point.len}B · stack ${point.stackSize ?? "-"}`;
        if (point.lib && point.addr) {
          const resolved = document.createElement("span");
          resolved.textContent = `resolved ${point.addr}`;
          description.append(title, metadata, resolved);
        } else {
          description.append(title, metadata);
        }

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "remove-command";
        remove.textContent = "Remove";
        remove.addEventListener("click", () => {
          try {
            sendCommand(buildUnobserveCommand(point));
          } catch (error) {
            setFormError(error.message);
          }
        });

        row.append(description, remove);
        fragment.append(row);
      });
    }

    points.replaceChildren(fragment);
  }

  function editSavedConfig(config) {
    editingConfigId = config.id;
    setRadioValue("processKind", "package");
    packageInput.value = config.packageName || "";
    pidInput.value = "";
    const isLibrary = Boolean(config.lib && config.offset);
    setRadioValue("targetKind", isLibrary ? "lib" : "abs");
    form.addr.value = isLibrary ? "" : (config.addr || "");
    form.lib.value = config.lib || "";
    form.offset.value = config.offset || "";
    setRadioValue("mode", config.mode || "R");
    setRadioValue("len", String(config.len || 4));
    form.stackSize.value = String(config.stackSize ?? DEFAULT_STACK_SIZE);
    configNote.value = config.note || "";
    saveConfigInput.checked = true;
    saveConfigButton.textContent = "Update configuration";
    syncProcessKind();
    syncTargetKind();
    syncModeLength();
    syncSaveConfig();
  }

  function renderSavedConfigs() {
    savedConfigCount.textContent = String(model.savedConfigs.length);
    if (editingConfigId && !model.savedConfigs.some((config) => config.id === editingConfigId)) {
      resetConfigEditing();
    }
    const fragment = document.createDocumentFragment();
    if (model.savedConfigs.length === 0) {
      fragment.append(createEmptyState(document, "No saved configurations"));
    } else {
      model.savedConfigs.forEach((config) => {
        const row = document.createElement("div");
        row.className = "point-row";
        const description = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = config.packageName || "Unknown package";
        const metadata = document.createElement("span");
        const display = config.display ||
          (config.lib && config.offset ? `${config.lib}+${config.offset}` : config.addr);
        metadata.textContent = `${display || "-"} 路 ${config.mode}/${config.len}B 路 stack ${config.stackSize ?? "-"}`;
        description.append(title, metadata);
        if (config.note) {
          const note = document.createElement("p");
          note.className = "config-note";
          note.textContent = config.note;
          description.append(note);
        }

        const actions = document.createElement("div");
        actions.className = "config-actions";
        const apply = document.createElement("button");
        apply.type = "button";
        apply.className = "ghost-command";
        apply.textContent = "Apply";
        apply.addEventListener("click", () => {
          try {
            sendCommand(buildApplyConfigCommand(config.id));
          } catch (error) {
            setFormError(error.message);
          }
        });
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "ghost-command";
        edit.textContent = "Edit";
        edit.addEventListener("click", () => editSavedConfig(config));
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "remove-command";
        remove.textContent = "Delete";
        remove.addEventListener("click", () => {
          if (typeof window.confirm === "function" && !window.confirm("Delete this saved configuration?")) {
            return;
          }
          try {
            sendCommand(buildDeleteConfigCommand(config.id));
          } catch (error) {
            setFormError(error.message);
          }
        });
        actions.append(apply, edit, remove);
        row.append(description, actions);
        fragment.append(row);
      });
    }
    savedConfigs.replaceChildren(fragment);
  }

  function renderEvents() {
    eventCount.textContent = String(model.events.length);
    const fragment = document.createDocumentFragment();

    if (model.events.length === 0) {
      fragment.append(createEmptyState(document, "Waiting for breakpoint hits…"));
    } else {
      model.events.forEach((event, index) => {
        fragment.append(
          createEventCard(document, event, model.backtraceMode || "fp", index === 0),
        );
      });
    }

    events.replaceChildren(fragment);
  }

  function prependNewestEvent(event) {
    prependEventCard(document, events, event, model.backtraceMode || "fp");
    eventCount.textContent = String(model.events.length);
  }

  function handleServerMessage(raw) {
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      return;
    }
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      return;
    }
    if (message.type === "ack") {
      if (!message.ok) {
        setFormError(String(message.error || "Request failed"));
        pendingBacktraceMode = null;
        pendingObserveAfterConfig = null;
        return;
      }
      setFormError();
      if (pendingObserveAfterConfig) {
        const command = pendingObserveAfterConfig;
        pendingObserveAfterConfig = null;
        sendCommand(command);
        return;
      }
      if (pendingBacktraceMode) {
        model = { ...model, backtraceMode: pendingBacktraceMode };
        pendingBacktraceMode = null;
        syncBacktraceModeUi();
      }
      return;
    }

    const nextModel = reduceServerMessage(model, message);
    if (nextModel === model) return;
    model = nextModel;
    if (message.type === "state") {
      renderPoints();
      renderSavedConfigs();
      syncBacktraceModeUi();
    }
    if (message.type === "event") prependNewestEvent(model.events[0]);
  }

  function scheduleReconnect() {
    if (reconnectTimer !== null) return;
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, RECONNECT_DELAY_MS);
  }

  function connect() {
    if (
      socket &&
      (socket.readyState === window.WebSocket.OPEN ||
        socket.readyState === window.WebSocket.CONNECTING)
    ) {
      return;
    }

    setConnectionState("connecting");
    const scheme = window.location.protocol === "https:" ? "wss" : "ws";
    const activeSocket = new window.WebSocket(`${scheme}://${window.location.host}/ws`);
    socket = activeSocket;

    activeSocket.addEventListener("open", () => {
      if (socket !== activeSocket) return;
      setConnectionState("connected");
      setFormError();
    });
    activeSocket.addEventListener("message", (event) => {
      if (socket === activeSocket && typeof event.data === "string") {
        handleServerMessage(event.data);
      }
    });
    activeSocket.addEventListener("close", () => {
      if (socket !== activeSocket) return;
      socket = null;
      pendingObserveAfterConfig = null;
      setConnectionState("disconnected");
      scheduleReconnect();
    });
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const values = Object.fromEntries(new window.FormData(form).entries());
    try {
      if (pendingObserveAfterConfig) {
        throw new Error("Configuration save is still in progress");
      }
      const command = buildObserveCommand(values);
      if (saveConfigInput.checked) {
        const saveCommand = buildSaveConfigCommand(values, editingConfigId);
        pendingObserveAfterConfig = command;
        if (!sendCommand(saveCommand)) {
          pendingObserveAfterConfig = null;
          return;
        }
        setFormError();
        return;
      }
      if (sendCommand(command)) setFormError();
    } catch (error) {
      setFormError(error.message);
    }
  });

  modeGroup.addEventListener("change", () => {
    syncModeLength();
  });

  processKindGroup.addEventListener("change", syncProcessKind);
  targetKindGroup.addEventListener("change", syncTargetKind);
  saveConfigInput.addEventListener("change", syncSaveConfig);

  saveConfigButton.addEventListener("click", () => {
    const values = Object.fromEntries(new window.FormData(form).entries());
    try {
      const command = buildSaveConfigCommand(values, editingConfigId);
      if (sendCommand(command)) setFormError();
    } catch (error) {
      setFormError(error.message);
    }
  });

  btGroup.addEventListener("change", () => {
    try {
      const mode = new window.FormData(form).get("backtraceMode");
      const command = buildConfigCommand({ backtraceMode: mode });
      pendingBacktraceMode = command.backtraceMode;
      if (!sendCommand(command)) pendingBacktraceMode = null;
    } catch (error) {
      setFormError(error.message);
    }
  });

  clearEvents.addEventListener("click", () => {
    model = clearEventsModel(model);
    renderEvents();
  });

  syncProcessKind();
  syncTargetKind();
  syncModeLength();
  syncSaveConfig();
  syncBacktraceModeUi();
  renderPoints();
  renderSavedConfigs();
  renderEvents();
  connect();
}

function createEmptyState(document, text) {
  const empty = document.createElement("p");
  empty.className = "empty-state";
  empty.textContent = text;
  return empty;
}

function createValueRow(document, label, value) {
  const row = document.createElement("div");
  row.className = "kv";
  const name = document.createElement("span");
  name.textContent = label;
  const content = document.createElement("code");
  content.textContent = String(value ?? "-");
  row.append(name, content);
  return row;
}

if (typeof document !== "undefined" && typeof WebSocket !== "undefined") {
  startBrowserClient(document, window);
}
