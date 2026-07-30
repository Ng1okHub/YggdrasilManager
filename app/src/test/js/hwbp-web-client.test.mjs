import assert from "node:assert/strict";
import test from "node:test";
import {
  EVENT_LIMIT,
  RECONNECT_DELAY_MS,
  DEFAULT_STACK_SIZE,
  buildApplyConfigCommand,
  buildConfigCommand,
  buildDeleteConfigCommand,
  buildObserveCommand,
  buildSaveConfigCommand,
  buildUnobserveCommand,
  clearEventsModel,
  copyBacktraceToClipboard,
  createEventCard,
  formatBacktrace,
  initialModel,
  prependEventCard,
  reduceServerMessage,
  setBacktraceDeduplication,
  visibleEvents,
} from "../../main/assets/web/app.js";

test("state replaces observe points and backtrace mode", () => {
  const next = reduceServerMessage(initialModel(), {
    type: "state",
    backtraceMode: "dwarf",
    points: [{ pid: 12, addr: "0x1000", mode: "RW", len: 8 }],
    savedConfigs: [{ id: "system-lib" }],
  });
  assert.equal(next.points.length, 1);
  assert.equal(next.points[0].addr, "0x1000");
  assert.equal(next.backtraceMode, "dwarf");
  assert.equal(next.savedConfigs[0].id, "system-lib");
});

test("events prepend, assign local displayId, and cap at 500", () => {
  let model = initialModel();
  for (let seq = 0; seq < EVENT_LIMIT + 25; seq += 1) {
    model = reduceServerMessage(model, { type: "event", seq, regs: [] });
  }
  assert.equal(model.events.length, 500);
  assert.equal(model.events[0].seq, 524);
  assert.equal(model.events[0].displayId, 525);
  assert.equal(model.events.at(-1).seq, 25);
  assert.equal(model.events.at(-1).displayId, 26);
});

test("stack deduplication hides duplicate stacks without discarding hit history", () => {
  let model = initialModel();
  model = reduceServerMessage(model, browserEvent(1));
  model = reduceServerMessage(model, {
    ...browserEvent(2),
    tid: 99,
    comm: "another-thread",
  });

  model = setBacktraceDeduplication(model, true);
  assert.equal(model.events.length, 2);
  assert.equal(visibleEvents(model).length, 1);
  assert.equal(visibleEvents(model)[0].seq, 102);
  assert.equal(model.nextDisplayId, 3);

  model = reduceServerMessage(model, {
    ...browserEvent(3),
    watchedAddr: "0x2000",
  });
  assert.equal(model.events.length, 3);
  assert.equal(visibleEvents(model).length, 1);
  assert.equal(visibleEvents(model)[0].seq, 103);
  assert.equal(model.nextDisplayId, 4);

  const distinct = reduceServerMessage(model, {
    ...browserEvent(4),
    backtrace: [{
      ...browserEvent(4).backtrace[0],
      pc: "0x2001",
    }],
  });
  assert.equal(distinct.events.length, 4);
  assert.equal(visibleEvents(distinct).length, 2);
  assert.equal(visibleEvents(distinct)[0].displayId, 4);

  const withoutDeduplication = setBacktraceDeduplication(distinct, false);
  assert.deepEqual(
    visibleEvents(withoutDeduplication).map((event) => event.seq),
    [104, 103, 102, 101],
  );
});

test("backtrace formatting and copy preserve ordered frame details", async () => {
  const event = {
    ...browserEvent(1),
    backtrace: [
      { index: 0, pc: "0x2000", label: "libtarget.so+0x20" },
      { index: 1, pc: "0x2100", label: "0x2100" },
    ],
  };
  const expected = "#0 libtarget.so+0x20 (0x2000)\n#1 0x2100";
  assert.equal(formatBacktrace(event), expected);

  let copiedText = null;
  const clipboard = {
    writeText: async (text) => {
      copiedText = text;
    },
  };
  assert.equal(await copyBacktraceToClipboard(new FakeDocument(), event, clipboard), true);
  assert.equal(copiedText, expected);
});

test("backtrace copy falls back to a text selection on HTTP clipboard failures", async () => {
  const document = new FakeDocument();
  const event = browserEvent(1);
  const copied = await copyBacktraceToClipboard(document, event, {
    writeText: async () => {
      throw new Error("clipboard blocked");
    },
  });

  assert.equal(copied, true);
  assert.equal(document.copiedText, formatBacktrace(event));
});

test("event cards keep copy controls outside the disclosure and discard details bodies", () => {
  const document = new FakeDocument();
  const card = createEventCard(document, browserEvent(1));

  assert.equal(card.open, false);
  assert.equal(card.childElementCount, 2);
  assert.equal(card.firstElementChild.tagName, "SUMMARY");
  assert.equal(card.children[1].className, "event-tools");
  assert.equal(card.children[1].lastElementChild.tagName, "BUTTON");
  assert.equal(card.children[1].lastElementChild.textContent, "Copy stack");

  card.open = true;
  card.dispatchEvent({ type: "toggle" });
  const firstBody = card.lastElementChild;
  assert.equal(firstBody.className, "event-body");
  assert.equal(card.childElementCount, 3);

  card.open = false;
  card.dispatchEvent({ type: "toggle" });
  assert.equal(card.childElementCount, 2);

  card.open = true;
  card.dispatchEvent({ type: "toggle" });
  assert.notEqual(card.lastElementChild, firstBody);
});

test("incremental event cards preserve manual state and only keep the newest auto-open", () => {
  const document = new FakeDocument();
  const container = document.createElement("div");
  const first = prependEventCard(document, container, browserEvent(1));
  const second = prependEventCard(document, container, browserEvent(2));

  assert.equal(first.open, false);
  assert.equal(first.childElementCount, 2);
  assert.equal(second.open, true);

  const secondSummary = second.firstElementChild;
  secondSummary.dispatchEvent({ type: "click" });
  second.open = false;
  second.dispatchEvent({ type: "toggle" });
  secondSummary.dispatchEvent({ type: "click" });
  second.open = true;
  second.dispatchEvent({ type: "toggle" });

  const third = prependEventCard(document, container, browserEvent(3));
  assert.equal(second.open, true);
  assert.equal(third.open, true);

  const fourth = prependEventCard(document, container, browserEvent(4));
  assert.equal(third.open, false);
  const fourthSummary = fourth.firstElementChild;
  fourthSummary.dispatchEvent({ type: "click" });
  fourth.open = false;
  fourth.dispatchEvent({ type: "toggle" });

  prependEventCard(document, container, browserEvent(5));
  assert.equal(fourth.open, false);
});

test("incremental event cards cap summary nodes at the event limit", () => {
  const document = new FakeDocument();
  const container = document.createElement("div");
  for (let id = 1; id <= EVENT_LIMIT + 5; id += 1) {
    prependEventCard(document, container, browserEvent(id));
  }
  assert.equal(container.childElementCount, EVENT_LIMIT);
});

test("clear resets event list and display id counter", () => {
  let model = initialModel();
  model = reduceServerMessage(model, { type: "event", seq: 100, regs: [] });
  model = reduceServerMessage(model, { type: "event", seq: 101, regs: [] });
  assert.equal(model.events[0].displayId, 2);
  model = clearEventsModel(model);
  assert.equal(model.events.length, 0);
  assert.equal(model.nextDisplayId, 1);
  model = reduceServerMessage(model, { type: "event", seq: 999, regs: [] });
  assert.equal(model.events[0].displayId, 1);
  assert.equal(model.events[0].seq, 999);
});

test("commands normalize absolute and lib+offset targets", () => {
  assert.deepEqual(
    buildObserveCommand({ processKind: "pid", pid: "1234", addr: "7FF0001000", mode: "RW", len: "8" }),
    {
      type: "observe",
      pid: 1234,
      addr: "0x7ff0001000",
      mode: "RW",
      len: 8,
      singleThread: false,
      stackSize: DEFAULT_STACK_SIZE,
    },
  );
  assert.deepEqual(
    buildObserveCommand({
      pid: "99",
      processKind: "pid",
      targetKind: "lib",
      lib: "libfoo.so",
      offset: "0x1234",
      mode: "X",
      len: "4",
    }),
    {
      type: "observe",
      pid: 99,
      lib: "libfoo.so",
      offset: "0x1234",
      mode: "X",
      len: 4,
      singleThread: false,
      stackSize: DEFAULT_STACK_SIZE,
    },
  );
  assert.deepEqual(buildUnobserveCommand({ pid: 1234, addr: "0x1000" }), {
    type: "unobserve",
    pid: 1234,
    addr: "0x1000",
  });
  assert.deepEqual(buildConfigCommand({ backtraceMode: "DWARF" }), {
    type: "config",
    backtraceMode: "dwarf",
  });
  assert.throws(
    () => buildObserveCommand({
      pid: "99",
      processKind: "pid",
      targetKind: "lib",
      lib: "libfoo.so",
      offset: "-1",
      mode: "RW",
      len: "8",
    }),
    /non-negative/,
  );
});

test("package targets and saved configuration commands preserve reusable fields", () => {
  assert.deepEqual(
    buildObserveCommand({
      packageName: "com.example.target",
      targetKind: "lib",
      lib: "libfoo.so",
      offset: "0x20",
      mode: "RW",
      len: "8",
      stackSize: "256",
    }),
    {
      type: "observe",
      packageName: "com.example.target",
      mode: "RW",
      len: 8,
      singleThread: false,
      stackSize: 256,
      lib: "libfoo.so",
      offset: "0x20",
    },
  );
  assert.deepEqual(
    buildSaveConfigCommand({
      packageName: "com.example.target",
      targetKind: "lib",
      lib: "libfoo.so",
      offset: "0x20",
      mode: "RW",
      len: "8",
      stackSize: "256",
      note: "watch entry",
    }, "system-lib"),
    {
      type: "saveConfig",
      id: "system-lib",
      packageName: "com.example.target",
      mode: "RW",
      len: 8,
      singleThread: false,
      stackSize: 256,
      lib: "libfoo.so",
      offset: "0x20",
      note: "watch entry",
    },
  );
  assert.deepEqual(buildApplyConfigCommand("system-lib"), {
    type: "applyConfig",
    id: "system-lib",
  });
  assert.deepEqual(buildDeleteConfigCommand("system-lib"), {
    type: "deleteConfig",
    id: "system-lib",
  });
  assert.throws(
    () => buildSaveConfigCommand({
      processKind: "pid",
      pid: "1234",
      addr: "0x1000",
      mode: "R",
      len: "4",
    }),
    /require an app package name/,
  );
});

test("reconnect policy is three seconds", () => {
  assert.equal(RECONNECT_DELAY_MS, 3000);
});

function browserEvent(displayId) {
  return {
    type: "event",
    displayId,
    seq: displayId + 100,
    dropped: 0,
    comm: "target",
    tid: 42,
    tgid: 42,
    watchedAddr: "0x1000",
    pc: "0x2000",
    sp: "0x3000",
    backtraceMode: "dwarf",
    backtrace: [{ index: 0, pc: "0x2000", label: "libtarget.so+0x20" }],
    regs: ["0x1", "0x2"],
    stack: { copied: 32, requested: 64, addr: "0x3000" },
  };
}

class FakeDocument {
  constructor() {
    this.body = new FakeElement("body");
    this.copiedText = null;
  }

  createElement(tagName) {
    return new FakeElement(tagName);
  }

  execCommand(command) {
    if (command !== "copy") return false;
    this.copiedText = this.body.lastElementChild?.value ?? null;
    return true;
  }
}

class FakeElement {
  constructor(tagName) {
    this.tagName = String(tagName).toUpperCase();
    this.children = [];
    this.parentElement = null;
    this.className = "";
    this.dataset = {};
    this.open = false;
    this.textContent = "";
    this.value = "";
    this.style = {};
    this.listeners = new Map();
  }

  get classList() {
    return {
      contains: (name) => this.className.split(/\s+/).includes(name),
    };
  }

  get childElementCount() {
    return this.children.length;
  }

  get firstElementChild() {
    return this.children[0] ?? null;
  }

  get lastElementChild() {
    return this.children.at(-1) ?? null;
  }

  append(...nodes) {
    nodes.forEach((node) => this.insert(node, this.children.length));
  }

  prepend(...nodes) {
    [...nodes].reverse().forEach((node) => this.insert(node, 0));
  }

  replaceChildren(...nodes) {
    this.children.forEach((child) => {
      child.parentElement = null;
    });
    this.children = [];
    this.append(...nodes);
  }

  insert(node, index) {
    node.remove();
    node.parentElement = this;
    this.children.splice(index, 0, node);
  }

  remove() {
    if (this.parentElement === null) return;
    const siblings = this.parentElement.children;
    const index = siblings.indexOf(this);
    if (index >= 0) siblings.splice(index, 1);
    this.parentElement = null;
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  setAttribute() {}

  focus() {}

  select() {}

  dispatchEvent(event) {
    for (const listener of this.listeners.get(event.type) ?? []) {
      listener.call(this, event);
    }
    return true;
  }
}
