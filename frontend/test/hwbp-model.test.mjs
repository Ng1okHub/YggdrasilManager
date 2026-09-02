import assert from "node:assert/strict";
import test from "node:test";
import {
  HWBP_EVENT_LIMIT,
  DEFAULT_STACK_SIZE,
  buildHwbpApplyConfigCommand,
  buildHwbpConfigCommand,
  buildHwbpDeleteConfigCommand,
  buildHwbpObserveCommand,
  buildHwbpSaveConfigCommand,
  buildHwbpUnobserveCommand,
  clearHwbpEvents,
  formatHwbpBacktrace,
  initialHwbpState,
  reduceHwbp,
  visibleHwbpEvents,
} from "../src/features/hwbp/model.ts";

test("HWBP state replaces points, saved configs, and backtrace mode", () => {
  const state = reduceHwbp(initialHwbpState(), {
    type: "state",
    backtraceMode: "dwarf",
    points: [{ pid: 12, addr: "0x1000", mode: "RW", len: 8 }],
    savedConfigs: [{ id: "system-lib" }],
  });
  assert.equal(state.points[0].addr, "0x1000");
  assert.equal(state.savedConfigs[0].id, "system-lib");
  assert.equal(state.backtraceMode, "dwarf");
});

test("HWBP events receive local display ids and are capped", () => {
  let state = initialHwbpState();
  for (let seq = 0; seq < HWBP_EVENT_LIMIT + 25; seq += 1) {
    state = reduceHwbp(state, { type: "event", seq });
  }
  assert.equal(state.events.length, HWBP_EVENT_LIMIT);
  assert.equal(state.events[0].seq, HWBP_EVENT_LIMIT + 24);
  assert.equal(state.events[0].displayId, HWBP_EVENT_LIMIT + 25);
  assert.equal(state.events.at(-1).seq, 25);
});

test("HWBP stack deduplication keeps only the newest matching backtrace", () => {
  let state = initialHwbpState();
  state = reduceHwbp(state, { type: "event", seq: 1, backtrace: [{ pc: "0x2000", label: "foo" }] });
  state = reduceHwbp(state, { type: "event", seq: 2, backtrace: [{ pc: "0x2000", label: "foo" }] });
  state = { ...state, deduplicateStacks: true };
  assert.deepEqual(visibleHwbpEvents(state).map((event) => event.seq), [2]);
  assert.equal(state.events.length, 2);
});

test("HWBP backtrace formatting preserves frame details", () => {
  assert.equal(
    formatHwbpBacktrace({ backtrace: [
      { index: 0, pc: "0x2000", label: "libtarget.so+0x20" },
      { index: 1, pc: "0x2100", label: "0x2100" },
    ] }),
    "#0 libtarget.so+0x20 (0x2000)\n#1 0x2100",
  );
});

test("HWBP commands normalize process and library targets", () => {
  assert.deepEqual(buildHwbpObserveCommand({
    processKind: "pid", pid: "1234", packageName: "", targetKind: "abs",
    addr: "7FF0001000", lib: "", offset: "", mode: "RW", len: "8",
    stackSize: "", backtraceMode: "fp", note: "",
  }), {
    type: "observe", pid: 1234, addr: "0x7ff0001000", mode: "RW", len: 8,
    singleThread: false, stackSize: DEFAULT_STACK_SIZE,
  });
  assert.deepEqual(buildHwbpObserveCommand({
    processKind: "package", packageName: "com.example.target", pid: "",
    targetKind: "lib", addr: "", lib: "libfoo.so", offset: "0x20", mode: "X", len: "8",
    stackSize: "256", backtraceMode: "fp", note: "",
  }), {
    type: "observe", packageName: "com.example.target", mode: "X", len: 4,
    singleThread: false, stackSize: 256, lib: "libfoo.so", offset: "0x20",
  });
});

test("HWBP saved config and mutation commands preserve ids", () => {
  const values = {
    processKind: "package", packageName: "com.example.target", pid: "",
    targetKind: "abs", addr: "0x1000", lib: "", offset: "", mode: "R", len: "4",
    stackSize: "256", backtraceMode: "fp", note: "watch entry",
  };
  assert.equal(buildHwbpSaveConfigCommand(values, "system-lib").id, "system-lib");
  assert.deepEqual(buildHwbpUnobserveCommand({ pid: 1234, addr: "0x1000", mode: "R", len: 4 }), { type: "unobserve", pid: 1234, addr: "0x1000" });
  assert.deepEqual(buildHwbpConfigCommand("DWARF"), { type: "config", backtraceMode: "dwarf" });
  assert.deepEqual(buildHwbpApplyConfigCommand("system-lib"), { type: "applyConfig", id: "system-lib" });
  assert.deepEqual(buildHwbpDeleteConfigCommand("system-lib"), { type: "deleteConfig", id: "system-lib" });
});

test("clearing HWBP events resets display ids", () => {
  let state = reduceHwbp(initialHwbpState(), { type: "event", seq: 100 });
  state = clearHwbpEvents(state);
  state = reduceHwbp(state, { type: "event", seq: 999 });
  assert.equal(state.events[0].displayId, 1);
  assert.equal(state.events[0].seq, 999);
});
