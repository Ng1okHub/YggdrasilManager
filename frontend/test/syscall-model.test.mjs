import assert from "node:assert/strict";
import test from "node:test";
import {
  MAX_PROCESS_ID,
  MAX_UID,
  createSyscallObserveCommand,
  createSyscallUnobserveCommand,
  filterEventsBySearch,
  filterSyscallEvents,
  initialSyscallState,
  normalizeInstalledApps,
  normalizeSyscallCatalog,
  observedSyscallsFromRules,
  reduceSyscall,
  selectSyscall,
  setSyscallSearch,
} from "../src/features/syscall/model.ts";

const event = (overrides = {}) => ({
  type: "event", seq: "0x10", callId: "0x77", phase: "enter", tid: 42, tgid: 42,
  syscallNr: 63, comm: "RenderThread", argsBefore: ["0x1"], args: ["0x2"], ...overrides,
});

test("Syscall exit pairs with its matching enter and preserves arguments", () => {
  let state = reduceSyscall(initialSyscallState(), event());
  state = reduceSyscall(state, event({ seq: "0x11", phase: "exit", return: "0xffffffffffffffff", argsBefore: ["0x3"], args: ["0x4"] }));
  assert.equal(state.events.length, 1);
  assert.equal(state.events[0].phase, "complete");
  assert.equal(state.events[0].entrySeq, "0x10");
  assert.deepEqual(state.events[0].args, ["0x2"]);
});

test("Syscall state keeps the catalog entries the server described", () => {
  const state = reduceSyscall(initialSyscallState(), {
    type: "state", rules: [{ syscallNr: 64, scope: "tgid", targetId: 4321, phase: "both" }],
    listener: { state: "running", error: null },
    syscallCatalog: [
      { nr: 64, name: "write", availability: "available", schema: true },
      { nr: 63, name: "read", availability: "available", schema: true, enterOnly: false },
      { nr: 64, name: "duplicate", availability: "available", schema: true },
    ],
  });
  assert.deepEqual(state.catalog.map((entry) => entry.nr), [63, 64]);
  assert.deepEqual(state.selectedSyscallNr, 64);
  assert.equal(state.listener.state, "running");
  // The last entry for a number wins, matching the server's own ordering.
  assert.equal(state.catalog[1].name, "duplicate");
  assert.equal(state.catalog[0].schema, true);
});

test("Syscall catalog entries outside the offered range are dropped", () => {
  // 0 (io_setup) and 292+ are not part of the arm64 selector surface.
  const entries = normalizeSyscallCatalog([
    { nr: 0, name: "io_setup" },
    { nr: 292, name: "io_pgetevents" },
    { nr: 291, name: "statx", availability: "available" },
    { nr: 180, name: "mq_open", availability: "kernel_disabled" },
    { nr: 245, name: "(reserved)", availability: "arch_unsupported" },
  ]);
  assert.deepEqual(entries.map((entry) => entry.nr), [180, 245, 291]);
  assert.deepEqual(entries.map((entry) => entry.availability), ["kernel_disabled", "arch_unsupported", "available"]);
});

test("installed apps are normalized, deduplicated, and searchable by caller", () => {
  const apps = normalizeInstalledApps({ apps: [
    { packageName: " com.example.Zeta ", label: " Zeta App ", uid: "10042" },
    { packageName: "com.example.alpha", label: "", uid: 10043 },
    { packageName: "com.example.alpha", label: "duplicate", uid: 10044 },
    { packageName: "", label: "missing", uid: 10045 },
  ] });
  assert.deepEqual(apps, [
    { packageName: "com.example.alpha", label: "com.example.alpha", uid: 10043, isSystem: false },
    { packageName: "com.example.Zeta", label: "Zeta App", uid: 10042, isSystem: false },
  ]);
});

test("Syscall search matches every nested value and every term", () => {
  const events = [event({ name: "openat", tid: 42, captures: [{ value: "/data/app" }], backtraceFrames: [{ label: "libc.so" }] }), event({ name: "read", comm: "Binder", tid: 7 })];
  assert.deepEqual(filterEventsBySearch(events, "OPENAT"), [events[0]]);
  assert.deepEqual(filterEventsBySearch(events, "renderthread 42"), [events[0]]);
  assert.deepEqual(filterEventsBySearch(events, "data libc.so"), [events[0]]);
  assert.deepEqual(filterEventsBySearch(events, "   "), events);
  assert.deepEqual(filterEventsBySearch(events, "missing"), []);
});

test("Syscall selected filter composes with text search", () => {
  let state = reduceSyscall(initialSyscallState(), { type: "state", rules: [{ syscallNr: 63, scope: "tgid", targetId: 1, phase: "both" }] });
  state = reduceSyscall(state, event({ syscallNr: 63, name: "read" }));
  state = reduceSyscall(state, event({ syscallNr: 64, name: "write" }));
  state = selectSyscall(state, 63);
  state = setSyscallSearch(state, "READ 42");
  assert.equal(filterSyscallEvents(state).length, 1);
});

test("Syscall observe and unobserve commands validate target ranges", () => {
  assert.deepEqual(createSyscallObserveCommand({ syscallNr: "64", scope: "tgid", targetId: "4321", phase: "both", captureBacktrace: false, backtraceStackSize: "4096" }), {
    type: "observe", syscallNr: 64, scope: "tgid", targetId: 4321, phase: "both", backtrace: false,
  });
  assert.deepEqual(createSyscallObserveCommand({ syscallNr: 63, scope: "uid", targetId: String(MAX_UID), phase: "exit", captureBacktrace: true, backtraceStackSize: 8192 }), {
    type: "observe", syscallNr: 63, scope: "uid", targetId: MAX_UID, phase: "exit", backtrace: true, backtraceStackSize: 8192,
  });
  assert.deepEqual(createSyscallUnobserveCommand({ syscallNr: 64, scope: "tgid", targetId: 4321, phase: "both" }), { type: "unobserve", syscallNr: 64, scope: "tgid", targetId: 4321, phase: "both" });
  // The picker offers 1..291 only, so the boundaries are the selector range.
  assert.deepEqual(createSyscallObserveCommand({ syscallNr: "291", scope: "tgid", targetId: "1", phase: "enter", captureBacktrace: false, backtraceStackSize: "" }).syscallNr, 291);
  assert.deepEqual(createSyscallObserveCommand({ syscallNr: "1", scope: "tgid", targetId: "1", phase: "enter", captureBacktrace: false, backtraceStackSize: "" }).syscallNr, 1);
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 0, scope: "tgid", targetId: 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }), /1 至 291/);
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 292, scope: "tgid", targetId: 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }), /1 至 291/);
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 437, scope: "tgid", targetId: 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }), /1 至 291/);
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 64, scope: "tgid", targetId: MAX_PROCESS_ID + 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }));
});

test("supported syscall and rule selectors are sorted and deduplicated", () => {
  assert.deepEqual(normalizeSyscallCatalog([{ nr: 64 }, { nr: 63 }, { nr: 64, name: "write" }, { nr: -1 }]).map((entry) => entry.nr), [63, 64]);
  assert.deepEqual(observedSyscallsFromRules([{ syscallNr: 64 }, { syscallNr: 64 }, { syscallNr: 56 }]).map((entry) => entry.nr), [56, 64]);
  // Rules carry names from the server, so a rule-only list still renders names.
  assert.deepEqual(observedSyscallsFromRules([{ syscallNr: 56, name: "openat" }]).map((entry) => entry.name), ["openat"]);
});
