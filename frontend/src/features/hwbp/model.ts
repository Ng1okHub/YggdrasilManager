export const HWBP_EVENT_LIMIT = 500;
export const DEFAULT_STACK_SIZE = 4096;

export type BacktraceMode = "fp" | "dwarf";
export type HwbpMode = "R" | "W" | "RW" | "X";

export interface HwbpFrame {
  index?: number;
  pc?: string;
  label?: string;
  module?: string | null;
  offset?: string | null;
}

export interface HwbpPoint {
  pid: number;
  addr: string;
  mode: string;
  len: number;
  flags?: number;
  stackSize?: number;
  packageName?: string;
  lib?: string;
  offset?: string;
  display?: string;
}

export interface HwbpConfig {
  id: string;
  packageName: string;
  mode: string;
  len: number;
  flags?: number;
  stackSize?: number;
  note?: string;
  addr?: string;
  lib?: string;
  offset?: string;
  display?: string;
}

export interface HwbpEvent {
  type?: string;
  displayId?: number;
  seq?: number | string;
  dropped?: number | string;
  comm?: string;
  tid?: number;
  tgid?: number;
  watchedAddr?: string;
  pc?: string;
  sp?: string;
  pstate?: string;
  stack?: { addr?: string; requested?: number; copied?: number };
  backtraceMode?: BacktraceMode;
  backtraceNote?: string | null;
  backtrace?: HwbpFrame[];
  regs?: string[];
}

export interface HwbpState {
  points: HwbpPoint[];
  savedConfigs: HwbpConfig[];
  events: HwbpEvent[];
  nextDisplayId: number;
  backtraceMode: BacktraceMode;
  deduplicateStacks: boolean;
  connection: "connecting" | "open" | "closed" | "error";
  error: string | null;
}

export const initialHwbpState = (): HwbpState => ({
  points: [],
  savedConfigs: [],
  events: [],
  nextDisplayId: 1,
  backtraceMode: "fp",
  deduplicateStacks: false,
  connection: "connecting",
  error: null,
});

export function reduceHwbp(state: HwbpState, message: Record<string, unknown>): HwbpState {
  if (message.type === "state") {
    return {
      ...state,
      points: Array.isArray(message.points) ? message.points as HwbpPoint[] : [],
      savedConfigs: Array.isArray(message.savedConfigs) ? message.savedConfigs as HwbpConfig[] : [],
      backtraceMode: normalizeBacktraceMode(message.backtraceMode ?? state.backtraceMode),
      error: null,
    };
  }
  if (message.type === "event") {
    const displayId = state.nextDisplayId > 0 ? state.nextDisplayId : 1;
    return {
      ...state,
      events: [{ ...message, displayId } as HwbpEvent, ...state.events].slice(0, HWBP_EVENT_LIMIT),
      nextDisplayId: displayId + 1,
    };
  }
  if (message.type === "ack" && message.ok === false) {
    return { ...state, error: String(message.error || "操作失敗") };
  }
  return state;
}

export function setHwbpConnection(state: HwbpState, connection: HwbpState["connection"]): HwbpState {
  return { ...state, connection };
}

export function clearHwbpEvents(state: HwbpState): HwbpState {
  return { ...state, events: [], nextDisplayId: 1 };
}

export function setHwbpError(state: HwbpState, error: string | null): HwbpState {
  return { ...state, error };
}

export function visibleHwbpEvents(state: HwbpState): HwbpEvent[] {
  if (!state.deduplicateStacks) return state.events;
  const seen = new Set<string>();
  return state.events.filter((event) => {
    const key = JSON.stringify((event.backtrace ?? []).map((frame, index) => [
      frame.index ?? index, frame.pc ?? null, frame.module ?? null,
      frame.offset ?? null, frame.label ?? null,
    ]));
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export interface HwbpFormValues {
  processKind: "package" | "pid";
  packageName: string;
  pid: string;
  targetKind: "abs" | "lib";
  addr: string;
  lib: string;
  offset: string;
  mode: HwbpMode;
  len: string;
  stackSize: string;
  backtraceMode: BacktraceMode;
  note: string;
}

export function buildHwbpObserveCommand(values: HwbpFormValues): Record<string, unknown> {
  const process = buildProcessTarget(values);
  const mode = values.mode;
  const requestedLength = positiveInteger(values.len, "Length");
  if (![1, 2, 4, 8].includes(requestedLength)) throw new Error("Length must be 1, 2, 4, or 8");
  const stackSize = values.stackSize === "" ? DEFAULT_STACK_SIZE : nonNegativeInteger(values.stackSize, "Stack size");
  if (stackSize > 65536) throw new Error("Stack size must be between 0 and 65536");
  const definition: Record<string, unknown> = {
    mode,
    len: mode === "X" ? 4 : requestedLength,
    singleThread: false,
    stackSize,
  };
  if (values.targetKind === "lib") {
    const lib = values.lib.trim();
    if (!lib) throw new Error("Library name is required");
    return { type: "observe", ...process, ...definition, lib, offset: normalizeOffset(values.offset) };
  }
  return { type: "observe", ...process, ...definition, addr: normalizeAddress(values.addr) };
}

export function buildHwbpSaveConfigCommand(values: HwbpFormValues, id?: string): Record<string, unknown> {
  if (values.processKind !== "package") throw new Error("Saved configurations require an app package name");
  return {
    ...buildHwbpObserveCommand(values),
    type: "saveConfig",
    ...(id ? { id: normalizeConfigId(id) } : {}),
    note: values.note.trim(),
  };
}

export function buildHwbpUnobserveCommand(point: HwbpPoint): Record<string, unknown> {
  return { type: "unobserve", pid: positiveInteger(point.pid, "PID"), addr: normalizeAddress(point.addr) };
}

export function buildHwbpConfigCommand(mode: BacktraceMode): Record<string, unknown> {
  return { type: "config", backtraceMode: normalizeBacktraceMode(mode) };
}

export const buildHwbpApplyConfigCommand = (id: string) => ({ type: "applyConfig", id: normalizeConfigId(id) });
export const buildHwbpDeleteConfigCommand = (id: string) => ({ type: "deleteConfig", id: normalizeConfigId(id) });

export function normalizeBacktraceMode(value: unknown): BacktraceMode {
  const mode = String(value ?? "fp").trim().toLowerCase();
  if (mode !== "fp" && mode !== "dwarf") throw new Error("Backtrace mode must be fp or dwarf");
  return mode;
}

function buildProcessTarget(values: HwbpFormValues): Record<string, unknown> {
  if (values.processKind === "package") {
    const packageName = values.packageName.trim();
    if (!/^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(packageName)) {
      throw new Error("App package name is invalid");
    }
    return { packageName };
  }
  return { pid: positiveInteger(values.pid, "PID") };
}

function normalizeAddress(value: unknown): string {
  const match = String(value ?? "").trim().match(/^(?:0x)?([0-9a-f]+)$/i);
  if (!match || match[1].length > 16) throw new Error("Address must be a 64-bit hexadecimal value");
  return `0x${match[1].toLowerCase().replace(/^0+(?=[0-9a-f])/, "")}`;
}

function normalizeOffset(value: unknown): string {
  const text = String(value ?? "").trim();
  if (!text) throw new Error("Offset is required");
  if (/^(?:0x)?[0-9a-f]+$/i.test(text)) return normalizeAddress(text);
  const number = Number(text);
  if (!Number.isInteger(number) || number < 0) throw new Error("Offset must be a non-negative integer or hex value");
  return `0x${number.toString(16)}`;
}

function normalizeConfigId(value: unknown): string {
  const id = String(value ?? "").trim();
  if (!id || id.length > 128) throw new Error("Configuration id is invalid");
  return id;
}

function positiveInteger(value: unknown, label: string): number {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) throw new Error(`${label} must be a positive integer`);
  return number;
}

function nonNegativeInteger(value: unknown, label: string): number {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0) throw new Error(`${label} must be a non-negative integer`);
  return number;
}

export function formatHwbpBacktrace(event: HwbpEvent): string {
  const frames = event.backtrace ?? [];
  if (frames.length === 0) return "No frames";
  return frames.map((frame, index) => {
    const position = frame.index ?? index;
    const label = frame.label || frame.pc || "-";
    return `#${position} ${label}${frame.pc && frame.pc !== label ? ` (${frame.pc})` : ""}`;
  }).join("\n");
}
