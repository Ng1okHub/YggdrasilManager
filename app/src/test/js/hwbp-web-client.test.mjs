import assert from "node:assert/strict";
import test from "node:test";
import {
  EVENT_LIMIT,
  RECONNECT_DELAY_MS,
  buildObserveCommand,
  buildUnobserveCommand,
  initialModel,
  reduceServerMessage,
} from "../../main/assets/web/app.js";

test("state replaces observe points", () => {
  const next = reduceServerMessage(initialModel(), {
    type: "state",
    points: [{ pid: 12, addr: "0x1000", mode: "RW", len: 8 }],
  });
  assert.equal(next.points.length, 1);
  assert.equal(next.points[0].addr, "0x1000");
});

test("events prepend and cap at 500", () => {
  let model = initialModel();
  for (let seq = 0; seq < EVENT_LIMIT + 25; seq += 1) {
    model = reduceServerMessage(model, { type: "event", seq, regs: [] });
  }
  assert.equal(model.events.length, 500);
  assert.equal(model.events[0].seq, 524);
  assert.equal(model.events.at(-1).seq, 25);
});

test("commands normalize values to the wire protocol", () => {
  assert.deepEqual(
    buildObserveCommand({ pid: "1234", addr: "7FF0001000", mode: "RW", len: "8" }),
    {
      type: "observe",
      pid: 1234,
      addr: "0x7ff0001000",
      mode: "RW",
      len: 8,
      singleThread: false,
      stackSize: 256,
    },
  );
  assert.deepEqual(buildUnobserveCommand({ pid: 1234, addr: "0x1000" }), {
    type: "unobserve",
    pid: 1234,
    addr: "0x1000",
  });
});

test("reconnect policy is three seconds", () => {
  assert.equal(RECONNECT_DELAY_MS, 3000);
});
