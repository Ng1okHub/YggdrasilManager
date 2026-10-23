export const SYSCALL_EVENT_LIMIT = 1000;
export const MAX_PROCESS_ID = 2_147_483_647;
export const MAX_UID = 4_294_967_295;

export type Scope = "tgid" | "tid" | "uid";
export type Phase = "enter" | "exit" | "both";
export type BacktraceMode = "fp" | "dwarf";

export interface SyscallCatalogEntry { nr: number; name: string; }
export interface SyscallRule { syscallNr: number; name?: string; scope: Scope; targetId: number; phase: Phase; backtrace?: boolean; backtraceStackSize?: number; }
export interface SyscallListener { state: string; error: string | null; }
export interface SyscallCapture { argIndex?: number; kind?: string; schema?: string; phase?: string; value?: string | null; status?: string; }
export interface SyscallEvent {
  type?: string;
  displayId?: number;
  seq?: string;
  entrySeq?: string;
  exitSeq?: string;
  callId?: string;
  phase?: "enter" | "exit" | "complete";
  tid?: number;
  tgid?: number;
  syscallNr?: number;
  name?: string;
  comm?: string;
  scope?: Scope;
  targetId?: number;
  argsBefore?: string[];
  args?: string[];
  captures?: SyscallCapture[];
  returnBefore?: string | null;
  return?: string | null;
  afterDropped?: boolean;
  dropped?: string;
  relayDropped?: string;
  backtraceMode?: BacktraceMode | null;
  backtraceNote?: string | null;
  backtraceFrames?: Array<{ index?: number; pc?: string; label?: string; module?: string | null; offset?: string | null }>;
}

export interface InstalledApp { packageName: string; label: string; uid: number; isSystem: boolean; }

export interface SyscallState {
  rules: SyscallRule[];
  listener: SyscallListener;
  supportedSyscalls: SyscallCatalogEntry[];
  events: SyscallEvent[];
  pending: Record<string, SyscallEvent>;
  nextDisplayId: number;
  selectedSyscallNr: number | null;
  eventSearchQuery: string;
  backtraceMode: BacktraceMode;
  connection: "connecting" | "open" | "closed" | "error";
  error: string | null;
}

export const initialSyscallState = (): SyscallState => ({
  rules: [],
  listener: { state: "starting", error: null },
  supportedSyscalls: [],
  events: [],
  pending: {},
  nextDisplayId: 1,
  selectedSyscallNr: null,
  eventSearchQuery: "",
  backtraceMode: "fp",
  connection: "connecting",
  error: null,
});

export function reduceSyscall(state: SyscallState, message: Record<string, unknown>): SyscallState {
  if (message.type === "state") {
    const rules = Array.isArray(message.rules) ? message.rules as SyscallRule[] : [];
    const supported = message.supportedSyscalls === undefined ? state.supportedSyscalls : normalizeSupportedSyscalls(message.supportedSyscalls);
    const requested = state.selectedSyscallNr;
    const selected = observedSyscallsFromRules(rules).some((entry) => entry.nr === requested) ? requested : observedSyscallsFromRules(rules)[0]?.nr ?? null;
    return {
      ...state,
      rules,
      listener: normalizeListener(message.listener, state.listener),
      supportedSyscalls: supported,
      selectedSyscallNr: selected,
      backtraceMode: normalizeBacktraceMode(message.backtraceMode ?? state.backtraceMode),
      error: null,
    };
  }
  if (message.type === "event") {
    const incoming = message as SyscallEvent;
    const phase = incoming.phase === "enter" || incoming.phase === "exit" ? incoming.phase : "enter";
    const key = callKey(incoming);
    if (phase === "exit" && key && state.pending[key]) {
      const entry = state.pending[key];
      const combined: SyscallEvent = {
        ...incoming,
        displayId: entry.displayId ?? state.nextDisplayId,
        phase: "complete",
        entrySeq: entry.seq,
        exitSeq: incoming.seq,
        argsBefore: entry.argsBefore ?? incoming.argsBefore,
        args: entry.args ?? incoming.args,
        captures: [...(entry.captures ?? []), ...(incoming.captures ?? [])],
      };
      const pending = { ...state.pending };
      delete pending[key];
      return {
        ...state,
        pending,
        events: [combined, ...state.events.filter((event) => event.displayId !== entry.displayId)].slice(0, SYSCALL_EVENT_LIMIT),
      };
    }
    const displayId = state.nextDisplayId;
    const pending = { ...state.pending };
    if (phase === "enter" && key) pending[key] = { ...incoming, displayId };
    return {
      ...state,
      pending,
      nextDisplayId: displayId + 1,
      events: [{ ...incoming, phase, displayId }, ...state.events].slice(0, SYSCALL_EVENT_LIMIT),
    };
  }
  if (message.type === "ack" && message.ok === false) return { ...state, error: String(message.error || "操作失敗") };
  return state;
}

function callKey(event: SyscallEvent): string | null {
  if (!event.callId || event.tid == null) return null;
  return `${event.tid}:${event.callId}`;
}

function normalizeListener(value: unknown, fallback: SyscallListener): SyscallListener {
  if (!value || typeof value !== "object") return fallback;
  const listener = value as Record<string, unknown>;
  return { state: String(listener.state ?? fallback.state), error: listener.error == null ? null : String(listener.error) };
}

export function setSyscallConnection(state: SyscallState, connection: SyscallState["connection"]): SyscallState { return { ...state, connection }; }
export function clearSyscallEvents(state: SyscallState): SyscallState { return { ...state, events: [], pending: {}, nextDisplayId: 1 }; }
export function setSyscallError(state: SyscallState, error: string | null): SyscallState { return { ...state, error }; }
export function selectSyscall(state: SyscallState, value: string | number | null): SyscallState { return { ...state, selectedSyscallNr: value == null || value === "" ? null : Number(value) }; }
export function setSyscallSearch(state: SyscallState, value: string): SyscallState { return { ...state, eventSearchQuery: value }; }

export function normalizeSupportedSyscalls(value: unknown): SyscallCatalogEntry[] {
  const source: unknown[] = Array.isArray(value) ? value : (value && typeof value === "object" && Array.isArray((value as Record<string, unknown>).supportedSyscalls) ? (value as Record<string, unknown>).supportedSyscalls as unknown[] : []);
  const map = new Map<number, SyscallCatalogEntry>();
  for (const item of source) {
    if (!item || typeof item !== "object") continue;
    const raw = item as Record<string, unknown>;
    const nr = Number(raw.nr);
    if (!Number.isInteger(nr) || nr < 0 || nr > 459) continue;
    const name = String(raw.name ?? syscallName(nr)).trim() || syscallName(nr);
    if (!map.has(nr) || map.get(nr)?.name === syscallName(nr)) map.set(nr, { nr, name });
  }
  return [...map.values()].sort((a, b) => a.nr - b.nr);
}

export function observedSyscallsFromRules(rules: SyscallRule[]): SyscallCatalogEntry[] {
  const map = new Map<number, SyscallCatalogEntry>();
  rules.forEach((rule) => { const nr = Number(rule.syscallNr); if (Number.isInteger(nr)) map.set(nr, { nr, name: rule.name || syscallName(nr) }); });
  return [...map.values()].sort((a, b) => a.nr - b.nr);
}

export function filterSyscallEvents(state: SyscallState): SyscallEvent[] {
  const selected = state.selectedSyscallNr;
  if (selected == null || !Number.isSafeInteger(selected)) return [];
  return filterEventsBySearch(
    state.events.filter((event) => Number(event.syscallNr) === selected),
    state.eventSearchQuery,
  );
}

/** Search every scalar value in an event, including nested captures/backtraces. */
export function filterEventsBySearch(events: SyscallEvent[], query: unknown): SyscallEvent[] {
  const terms = String(query ?? "")
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  const source = Array.isArray(events) ? events : [];
  if (terms.length === 0) return source;

  return source.filter((event) => {
    const values: string[] = [];
    const collect = (value: unknown): void => {
      if (value === null || value === undefined) return;
      if (Array.isArray(value)) {
        value.forEach(collect);
        return;
      }
      if (typeof value === "object") {
        Object.values(value as Record<string, unknown>).forEach(collect);
        return;
      }
      values.push(String(value));
    };
    collect(event);
    values.push(syscallName(event.syscallNr), event.name || "");
    const searchableText = values.filter(Boolean).join(" ").toLowerCase();
    return terms.every((term) => searchableText.includes(term));
  });
}

export function normalizeInstalledApps(value: unknown): InstalledApp[] {
  const source: unknown[] = Array.isArray(value) ? value : (value && typeof value === "object" && Array.isArray((value as Record<string, unknown>).apps) ? (value as Record<string, unknown>).apps as unknown[] : []);
  const map = new Map<string, InstalledApp>();
  for (const item of source) {
    if (!item || typeof item !== "object") continue;
    const raw = item as Record<string, unknown>;
    const packageName = String(raw.packageName ?? "").trim();
    const uid = Number(raw.uid);
    if (!packageName || !Number.isInteger(uid) || uid <= 0 || uid > MAX_UID || map.has(packageName)) continue;
    map.set(packageName, { packageName, label: String(raw.label ?? packageName).trim() || packageName, uid, isSystem: Boolean(raw.isSystem) });
  }
  return [...map.values()].sort((a, b) => a.packageName.localeCompare(b.packageName));
}

export function selectedPackageUid(apps: InstalledApp[], packageName: string): number {
  const app = apps.find((item) => item.packageName === packageName);
  if (!app) throw new Error("找不到選取的 App");
  return app.uid;
}

export function createSyscallObserveCommand(values: { syscallNr: unknown; scope: Scope; targetId: unknown; phase: Phase; captureBacktrace: boolean; backtraceStackSize: unknown }): Record<string, unknown> {
  const syscallNr = parseInteger(values.syscallNr, "Syscall 編號");
  if (syscallNr < 0 || syscallNr > 459) throw new Error("Syscall 編號必須介於 0 至 459");
  const targetId = parseInteger(values.targetId, "目標 ID");
  if (targetId <= 0 || targetId > (values.scope === "uid" ? MAX_UID : MAX_PROCESS_ID)) throw new Error("目標 ID 超出範圍");
  const command: Record<string, unknown> = { type: "observe", syscallNr, scope: values.scope, targetId, phase: values.phase, backtrace: Boolean(values.captureBacktrace) };
  if (values.captureBacktrace) {
    const size = values.backtraceStackSize === "" ? 4096 : parseInteger(values.backtraceStackSize, "Stack size");
    if (size < 0 || size > 65536) throw new Error("Stack size 必須介於 0 至 65536");
    command.backtraceStackSize = size;
  }
  return command;
}

export function createSyscallUnobserveCommand(rule: SyscallRule): Record<string, unknown> {
  return { type: "unobserve", syscallNr: Number(rule.syscallNr), scope: rule.scope, targetId: Number(rule.targetId), phase: rule.phase };
}
export function createSyscallConfigCommand(mode: BacktraceMode): Record<string, unknown> { return { type: "config", backtraceMode: normalizeBacktraceMode(mode) }; }
export const createRestartListenerCommand = () => ({ type: "restartListener" });

export function normalizeBacktraceMode(value: unknown): BacktraceMode { const mode = String(value ?? "fp").toLowerCase(); if (mode !== "fp" && mode !== "dwarf") throw new Error("Backtrace mode must be fp or dwarf"); return mode; }
function parseInteger(value: unknown, label: string): number { const number = Number(value); if (!Number.isInteger(number)) throw new Error(`${label} 必須是整數`); return number; }

const names: Record<number, string> = { 34: "mkdirat", 35: "unlinkat", 36: "symlinkat", 37: "linkat", 38: "renameat", 43: "statfs", 48: "faccessat", 49: "chdir", 56: "openat", 57: "close", 63: "read", 64: "write", 66: "lseek", 67: "pread64", 68: "pwrite64", 78: "readlinkat", 79: "newfstatat", 93: "exit", 94: "exit_group", 113: "clock_gettime", 200: "bind", 203: "connect", 220: "clone", 221: "execve", 222: "mmap", 226: "mprotect", 278: "getrandom", 291: "statx", 437: "openat2" };
export function syscallName(nr: unknown): string { return names[Number(nr)] ?? `syscall_${nr}`; }
