import assert from "node:assert/strict";
import test from "node:test";
import {
  EVENT_LIMIT,
  MAX_PROCESS_ID,
  MAX_UID,
  createObserveCommand,
  createUnobserveCommand,
  filterInstalledApps,
  formatCapture,
  formatReturn,
  initialModel,
  normalizeInstalledApps,
  normalizeSupportedSyscalls,
  reduceServerMessage,
  selectedPackageUid,
} from "../../main/assets/syscall-web/app.js";

test("an exit pairs with its matching entry and preserves entry arguments", () => {
  let model = reduceServerMessage(initialModel(), event({
    seq: "0x10",
    phase: "enter",
    callId: "0x77",
    tid: 42,
    syscallNr: 63,
    argsBefore: ["0x1"],
    args: ["0x2"],
  }));

  model = reduceServerMessage(model, event({
    seq: "0x11",
    phase: "exit",
    callId: "0x77",
    tid: 42,
    syscallNr: 63,
    argsBefore: ["0x3"],
    args: ["0x4"],
    return: "0xffffffffffffffff",
    afterDropped: true,
  }));

  assert.equal(model.events.length, 1);
  assert.equal(model.nextDisplayId, 2);
  assert.deepEqual(model.events[0], {
    ...event({
      seq: "0x11",
      phase: "exit",
      callId: "0x77",
      tid: 42,
      syscallNr: 63,
      argsBefore: ["0x3"],
      args: ["0x4"],
      return: "0xffffffffffffffff",
      afterDropped: true,
    }),
    displayId: 1,
    phase: "complete",
    entrySeq: "0x10",
    exitSeq: "0x11",
    argsBefore: ["0x1"],
    args: ["0x2"],
    captures: [],
    afterDropped: true,
  });
});

test("unmatched exits remain visible as independent records", () => {
  const model = reduceServerMessage(initialModel(), event({
    seq: "0x20",
    phase: "exit",
    callId: "0x99",
    tid: 42,
    syscallNr: 64,
  }));

  assert.equal(model.events.length, 1);
  assert.equal(model.events[0].phase, "exit");
  assert.equal(model.events[0].displayId, 1);
  assert.equal(model.nextDisplayId, 2);
});

test("paired calls retain decoded captures from enter and exit", () => {
  const entryCapture = {
    argIndex: 1,
    phase: "enter",
    kind: "cstring",
    schema: "none",
    value: '"/data/local/tmp/file"',
  };
  const exitCapture = {
    argIndex: 4,
    phase: "exit",
    kind: "struct",
    schema: "statx",
    value: "{size=42}",
  };
  let model = reduceServerMessage(initialModel(), event({
    phase: "enter",
    callId: "0x88",
    tid: 7,
    syscallNr: 291,
    captures: [entryCapture],
  }));

  model = reduceServerMessage(model, event({
    phase: "exit",
    callId: "0x88",
    tid: 7,
    syscallNr: 291,
    captures: [exitCapture],
  }));

  assert.deepEqual(model.events[0].captures, [entryCapture, exitCapture]);
});

test("state carries reader health without clearing the call history", () => {
  let model = reduceServerMessage(initialModel(), event({ callId: "0x41" }));
  model = reduceServerMessage(model, {
    type: "state",
    rules: [],
    listener: {
      state: "failed",
      error: "系統呼叫監聽器已停止（errno 32）",
    },
  });

  assert.equal(model.events.length, 1);
  assert.deepEqual(model.listener, {
    state: "failed",
    error: "系統呼叫監聽器已停止（errno 32）",
  });
});

test("supported syscall catalogs are normalized, deduplicated, and sorted", () => {
  assert.deepEqual(
    normalizeSupportedSyscalls([
      { nr: "63", name: "read" },
      { nr: 64 },
      { nr: 63, name: " read (decoded) " },
      { nr: -1, name: "invalid" },
      { nr: 460, name: "invalid" },
      { nr: 67.5, name: "invalid" },
      null,
    ]),
    [
      { nr: 63, name: "read (decoded)" },
      { nr: 64, name: "write" },
    ],
  );
  assert.deepEqual(normalizeSupportedSyscalls({ supportedSyscalls: [] }), []);
});

test("state updates retain the supported syscall catalog when it is omitted", () => {
  let model = reduceServerMessage(initialModel(), {
    type: "state",
    rules: [],
    listener: { state: "running", error: null },
    supportedSyscalls: [
      { nr: 64, name: "write" },
      { nr: 56, name: "openat" },
    ],
  });

  assert.deepEqual(model.supportedSyscalls, [
    { nr: 56, name: "openat" },
    { nr: 64, name: "write" },
  ]);

  model = reduceServerMessage(model, {
    type: "state",
    rules: [{ syscallNr: 64, scope: "uid", targetId: 10042, phase: "both" }],
    listener: { state: "running", error: null },
  });

  assert.deepEqual(model.supportedSyscalls, [
    { nr: 56, name: "openat" },
    { nr: 64, name: "write" },
  ]);
});

test("installed apps are normalized and searchable by package name case-insensitively", () => {
  const apps = normalizeInstalledApps({
    apps: [
      { packageName: " com.example.Zeta ", label: " Zeta App ", uid: "10042" },
      { packageName: "com.example.alpha", label: "", uid: 10043 },
      { packageName: "com.example.system", label: "System App", uid: 10044, isSystem: true },
      { packageName: "", label: "Missing package", uid: 10044 },
      { packageName: "com.example.invalid", label: "Invalid UID", uid: 0 },
      { packageName: "com.example.alpha", label: "Duplicate", uid: 10045 },
    ],
  });

  assert.deepEqual(apps, [
    { packageName: "com.example.alpha", label: "com.example.alpha", uid: 10043, isSystem: false },
    { packageName: "com.example.Zeta", label: "Zeta App", uid: 10042, isSystem: false },
    { packageName: "com.example.system", label: "System App", uid: 10044, isSystem: true },
  ]);
  assert.deepEqual(filterInstalledApps(apps, "EXAMPLE.zEtA"), [apps[1]]);
  assert.equal(apps[0].isSystem, false);
  assert.equal(apps[2].isSystem, true);
});

test("packages sharing one UID remain distinct selectable entries", () => {
  const apps = normalizeInstalledApps([
    { packageName: "com.example.shared.one", label: "Shared One", uid: 10123 },
    { packageName: "com.example.shared.two", label: "Shared Two", uid: 10123 },
  ]);

  assert.equal(apps.length, 2);
  assert.deepEqual(
    apps.map((app) => [app.packageName, app.uid]),
    [
      ["com.example.shared.one", 10123],
      ["com.example.shared.two", 10123],
    ],
  );
  assert.equal(apps.every((app) => app.isSystem === false), true);
});

test("selected packages resolve to the numeric UID sent to the backend", () => {
  const apps = normalizeInstalledApps([
    { packageName: "com.example.first", label: "First", uid: "10234" },
    { packageName: "com.example.second", label: "Second", uid: "10235" },
  ]);

  const uid = selectedPackageUid(apps, "com.example.second");
  assert.equal(uid, 10235);
  assert.equal(typeof uid, "number");
  assert.throws(() => selectedPackageUid(apps, "com.example.missing"), Error);
});

test("events retain only the configured newest-record limit", () => {
  let model = initialModel();
  for (let sequence = 0; sequence < EVENT_LIMIT + 5; sequence += 1) {
    model = reduceServerMessage(model, event({
      seq: `0x${sequence.toString(16)}`,
      phase: "enter",
      callId: `0x${(sequence + 1).toString(16)}`,
      tid: 1,
      syscallNr: 63,
    }));
  }

  assert.equal(model.events.length, EVENT_LIMIT);
  assert.equal(model.events[0].seq, `0x${(EVENT_LIMIT + 4).toString(16)}`);
  assert.equal(model.events.at(-1).seq, "0x5");
  assert.equal(model.events[0].displayId, EVENT_LIMIT + 5);
  assert.equal(model.events.at(-1).displayId, 6);
});

test("observe only accepts scoped positive targets and valid syscall phases", () => {
  assert.deepEqual(
    createObserveCommand({
      syscallNr: "64",
      scope: "tgid",
      targetId: "4321",
      phase: "both",
    }),
    {
      type: "observe",
      syscallNr: 64,
      scope: "tgid",
      targetId: 4321,
      phase: "both",
    },
  );
  assert.deepEqual(
    createObserveCommand({
      syscallNr: 63,
      scope: "uid",
      targetId: String(MAX_UID),
      phase: "exit",
    }),
    {
      type: "observe",
      syscallNr: 63,
      scope: "uid",
      targetId: MAX_UID,
      phase: "exit",
    },
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 460, scope: "tgid", targetId: 1, phase: "enter" }),
    /介於 0 至 459/,
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 64, scope: "pid", targetId: 1, phase: "enter" }),
    /無效的範圍/,
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 64, scope: "global", targetId: 0, phase: "enter" }),
    /無效的範圍/,
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 64, scope: "tid", targetId: 0, phase: "enter" }),
    /目標 ID 必須為正整數/,
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 64, scope: "uid", targetId: 0, phase: "enter" }),
    /UID 必須為正整數/,
  );
  assert.throws(
    () => createObserveCommand({ syscallNr: 64, scope: "uid", targetId: MAX_UID + 1, phase: "enter" }),
    /UID 必須介於 1 至 4294967295/,
  );
  ["tgid", "tid"].forEach((scope) => {
    assert.deepEqual(
      createObserveCommand({ syscallNr: 64, scope, targetId: MAX_PROCESS_ID, phase: "enter" }),
      { type: "observe", syscallNr: 64, scope, targetId: MAX_PROCESS_ID, phase: "enter" },
    );
    assert.throws(
      () => createObserveCommand({ syscallNr: 64, scope, targetId: MAX_PROCESS_ID + 1, phase: "enter" }),
      /目標 ID 必須介於 1 至 2147483647/,
    );
  });
  assert.throws(
    () => createObserveCommand({ syscallNr: 93, scope: "tgid", targetId: 1, phase: "both" }),
    /僅支援進入階段觀察/,
  );
});

test("observe rejects a blank syscall selection", () => {
  assert.throws(
    () => createObserveCommand({
      syscallNr: "   ",
      scope: "tgid",
      targetId: 4321,
      phase: "both",
    }),
    Error,
  );
});

test("unobserve retains legacy global and root UID selectors without a trace phase", () => {
  assert.deepEqual(
    createUnobserveCommand({ syscallNr: "64", scope: "uid", targetId: "10042", phase: "both" }),
    {
      type: "unobserve",
      syscallNr: 64,
      scope: "uid",
      targetId: 10042,
    },
  );
  assert.deepEqual(
    createUnobserveCommand({ syscallNr: 64, scope: "global", targetId: 0 }),
    {
      type: "unobserve",
      syscallNr: 64,
      scope: "global",
      targetId: 0,
    },
  );
  assert.deepEqual(
    createUnobserveCommand({ syscallNr: 64, scope: "uid", targetId: 0 }),
    {
      type: "unobserve",
      syscallNr: 64,
      scope: "uid",
      targetId: 0,
    },
  );
});

test("return formatter exposes kernel errno values while preserving u64 text", () => {
  assert.equal(formatReturn("0xffffffffffffffff"), "0xffffffffffffffff (-1)");
  assert.equal(formatReturn("0xfffffffffffff001"), "0xfffffffffffff001 (-4095)");
  assert.equal(formatReturn("0xfffffffffffff000"), "0xfffffffffffff000");
  assert.equal(formatReturn("0x0000000000000001"), "0x0000000000000001");
  assert.equal(formatReturn("invalid"), "invalid");
  assert.equal(formatReturn(null), "-");
});

test("capture formatter keeps decoded values and partial status visible", () => {
  assert.deepEqual(
    formatCapture({
      kind: "struct",
      schema: "sockaddr",
      phase: "enter",
      capturedLength: 16,
      originalLength: 28,
      status: "truncated|budget",
      address: "0xffff00001234",
      value: "{family=AF_INET address=127.0.0.1 port=443}",
    }),
    {
      label: "enter | sockaddr",
      value: "{family=AF_INET address=127.0.0.1 port=443}",
      meta: "16/28 B | truncated|budget | 0xffff00001234",
    },
  );
  assert.equal(formatCapture({ status: "fault" }).value, "<fault>");
});

function event(overrides = {}) {
  return {
    type: "event",
    seq: "0x1",
    dropped: "0x0",
    callId: "0x1",
    phase: "enter",
    tid: 1,
    tgid: 1,
    syscallNr: 63,
    argsBefore: ["0x0"],
    args: ["0x0"],
    ...overrides,
  };
}
