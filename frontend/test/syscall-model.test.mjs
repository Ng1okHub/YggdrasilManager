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
  normalizeSupportedSyscalls,
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

test("Syscall state normalizes rules, listener, and supported catalog", () => {
  const state = reduceSyscall(initialSyscallState(), {
    type: "state", rules: [{ syscallNr: 64, scope: "tgid", targetId: 4321, phase: "both" }],
    listener: { state: "running", error: null },
    supportedSyscalls: [{ nr: 64 }, { nr: 63, name: "read" }, { nr: 64, name: "duplicate" }],
  });
  assert.deepEqual(state.supportedSyscalls.map((entry) => entry.nr), [63, 64]);
  assert.equal(state.selectedSyscallNr, 64);
  assert.equal(state.listener.state, "running");
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
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 460, scope: "tgid", targetId: 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }));
  assert.throws(() => createSyscallObserveCommand({ syscallNr: 64, scope: "tgid", targetId: MAX_PROCESS_ID + 1, phase: "enter", captureBacktrace: false, backtraceStackSize: "" }));
});

test("supported syscall and rule selectors are sorted and deduplicated", () => {
  assert.deepEqual(normalizeSupportedSyscalls([{ nr: 64 }, { nr: 63 }, { nr: 64, name: "write" }, { nr: -1 }]).map((entry) => entry.nr), [63, 64]);
  assert.deepEqual(observedSyscallsFromRules([{ syscallNr: 64 }, { syscallNr: 64 }, { syscallNr: 56 }]).map((entry) => entry.nr), [56, 64]);
});
