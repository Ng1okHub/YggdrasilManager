export const EVENT_LIMIT = 500;
export const RECONNECT_DELAY_MS = 3000;

const MODES = new Set(["R", "W", "RW", "X"]);
const LENGTHS = new Set([1, 2, 4, 8]);

export function initialModel() {
  return {
    points: [],
    events: [],
  };
}

export function reduceServerMessage(model, message) {
  if (message?.type === "state") {
    return {
      ...model,
      points: Array.isArray(message.points) ? [...message.points] : [],
    };
  }
  if (message?.type === "event") {
    return {
      ...model,
      events: [message, ...model.events].slice(0, EVENT_LIMIT),
    };
  }
  return model;
}

export function buildObserveCommand(values) {
  const mode = String(values.mode ?? "").trim().toUpperCase();
  if (!MODES.has(mode)) {
    throw new Error("Mode must be R, W, RW, or X");
  }

  const requestedLength = positiveInteger(values.len, "Length");
  if (!LENGTHS.has(requestedLength)) {
    throw new Error("Length must be 1, 2, 4, or 8");
  }

  const stackSize = values.stackSize === undefined || values.stackSize === ""
    ? 256
    : nonNegativeInteger(values.stackSize, "Stack size");
  if (stackSize > 65536) {
    throw new Error("Stack size must be between 0 and 65536");
  }

  return {
    type: "observe",
    pid: positiveInteger(values.pid, "PID"),
    addr: normalizeAddress(values.addr),
    mode,
    len: mode === "X" ? 4 : requestedLength,
    singleThread: Boolean(values.singleThread),
    stackSize,
  };
}

export function buildUnobserveCommand(values) {
  return {
    type: "unobserve",
    pid: positiveInteger(values.pid, "PID"),
    addr: normalizeAddress(values.addr),
  };
}

function normalizeAddress(value) {
  const match = String(value ?? "").trim().match(/^(?:0x)?([0-9a-f]+)$/i);
  if (!match || match[1].length > 16) {
    throw new Error("Address must be a 64-bit hexadecimal value");
  }
  const digits = match[1].toLowerCase().replace(/^0+(?=[0-9a-f])/, "");
  return `0x${digits}`;
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

function startBrowserClient(document, window) {
  const connectionState = document.getElementById("connection-state");
  const form = document.getElementById("observe-form");
  const formError = document.getElementById("form-error");
  const points = document.getElementById("points");
  const pointCount = document.getElementById("point-count");
  const events = document.getElementById("events");
  const eventCount = document.getElementById("event-count");
  const clearEvents = document.getElementById("clear-events");
  const modeGroup = document.getElementById("mode-group");
  const lengthInputs = [...document.querySelectorAll('input[name="len"]')];

  let model = initialModel();
  let socket = null;
  let reconnectTimer = null;

  function setConnectionState(state) {
    connectionState.dataset.state = state;
    connectionState.textContent = {
      connected: "Connected",
      connecting: "Connecting",
      disconnected: "Disconnected",
    }[state];
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
        title.textContent = `PID ${point.pid}`;
        const metadata = document.createElement("span");
        metadata.textContent = `${point.addr} | ${point.mode} | ${point.len} B`;
        description.append(title, metadata);

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

  function renderEvents() {
    eventCount.textContent = String(model.events.length);
    const fragment = document.createDocumentFragment();

    if (model.events.length === 0) {
      fragment.append(createEmptyState(document, "Waiting for events"));
    } else {
      model.events.forEach((event) => {
        const details = document.createElement("details");
        details.className = "event-row";

        const summary = document.createElement("summary");
        const identity = document.createElement("span");
        identity.className = "event-identity";
        identity.textContent = `#${event.seq} ${event.comm ?? "unknown"}`;
        const context = document.createElement("span");
        context.className = "event-context";
        context.textContent = `tid=${event.tid} tgid=${event.tgid}`;
        summary.append(identity, context);

        if (Number(event.dropped) > 0) {
          const dropped = document.createElement("span");
          dropped.className = "dropped-count";
          dropped.textContent = `Dropped ${event.dropped}`;
          summary.append(dropped);
        }

        const body = document.createElement("div");
        body.className = "event-details";
        body.append(
          createValueRow(document, "Watched", event.watchedAddr),
          createValueRow(document, "PC", event.pc),
          createValueRow(document, "SP", event.sp),
        );

        const registers = document.createElement("div");
        registers.className = "register-grid";
        const registerValues = Array.isArray(event.regs) ? event.regs : [];
        registerValues.forEach((value, index) => {
          registers.append(createValueRow(document, `x${index}`, value));
        });
        body.append(registers);

        details.append(summary, body);
        fragment.append(details);
      });
    }

    events.replaceChildren(fragment);
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
      setFormError(message.ok ? "" : String(message.error || "Request failed"));
      return;
    }

    const nextModel = reduceServerMessage(model, message);
    if (nextModel === model) return;
    model = nextModel;
    if (message.type === "state") renderPoints();
    if (message.type === "event") renderEvents();
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
      setConnectionState("disconnected");
      scheduleReconnect();
    });
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const values = new window.FormData(form);
    try {
      const command = buildObserveCommand({
        pid: values.get("pid"),
        addr: values.get("addr"),
        mode: values.get("mode"),
        len: values.get("len"),
      });
      if (sendCommand(command)) setFormError();
    } catch (error) {
      setFormError(error.message);
    }
  });

  modeGroup.addEventListener("change", () => {
    const selectedMode = new window.FormData(form).get("mode");
    lengthInputs.forEach((input) => {
      input.disabled = selectedMode === "X" && input.value !== "4";
      if (selectedMode === "X" && input.value === "4") input.checked = true;
    });
  });

  clearEvents.addEventListener("click", () => {
    model = { ...model, events: [] };
    renderEvents();
  });

  renderPoints();
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
