import { useEffect, useMemo, useReducer, useRef, useState } from "preact/hooks";
import type { JSX } from "preact";
import { Button, BrandHeader, ConnectionPill, CopyButton, EmptyState, Field, SectionHeading } from "../../shared/ui";
import { WebSocketClient } from "../../shared/transport";
import { PackagePicker } from "../../shared/PackagePicker";
import {
  clearSyscallEvents,
  createRestartListenerCommand,
  createSyscallConfigCommand,
  createSyscallObserveCommand,
  createSyscallUnobserveCommand,
  filterSyscallEvents,
  initialSyscallState,
  normalizeBacktraceMode,
  normalizeInstalledApps,
  normalizeSupportedSyscalls,
  reduceSyscall,
  selectedPackageUid,
  selectSyscall,
  setSyscallConnection,
  setSyscallError,
  setSyscallSearch,
  observedSyscallsFromRules,
  type BacktraceMode,
  type InstalledApp,
  type Scope,
  type SyscallEvent,
  type SyscallRule,
  type SyscallState,
} from "./model";

type Action =
  | { kind: "message"; message: Record<string, unknown> }
  | { kind: "connection"; status: SyscallState["connection"] }
  | { kind: "error"; message: string | null }
  | { kind: "clear" }
  | { kind: "select"; value: string }
  | { kind: "search"; value: string };

function reducer(state: SyscallState, action: Action): SyscallState {
  if (action.kind === "message") return reduceSyscall(state, action.message);
  if (action.kind === "connection") return setSyscallConnection(state, action.status);
  if (action.kind === "error") return setSyscallError(state, action.message);
  if (action.kind === "clear") return clearSyscallEvents(state);
  if (action.kind === "select") return selectSyscall(state, action.value);
  return setSyscallSearch(state, action.value);
}

export function SyscallView() {
  const [state, dispatch] = useReducer(reducer, undefined, initialSyscallState);
  const [scope, setScope] = useState<Scope>("uid");
  const [targetId, setTargetId] = useState("");
  const [packageName, setPackageName] = useState("");
  const [packageQuery, setPackageQuery] = useState("");
  const [packageMenuOpen, setPackageMenuOpen] = useState(false);
  const [phase, setPhase] = useState<"enter" | "exit" | "both">("both");
  const [captureBacktrace, setCaptureBacktrace] = useState(false);
  const [backtraceStackSize, setBacktraceStackSize] = useState("4096");
  const [backtraceMode, setBacktraceMode] = useState<BacktraceMode>("fp");
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [status, setStatus] = useState("");
  const clientRef = useRef<WebSocketClient | null>(null);

  useEffect(() => {
    const client = new WebSocketClient("/ws", {
      onStatus: (connection) => { dispatch({ kind: "connection", status: connection }); setStatus(connection === "open" ? "已連線" : connection === "closed" ? "連線中斷，將自動重試" : ""); },
      onMessage: (message) => dispatch({ kind: "message", message }),
      onError: (error) => dispatch({ kind: "error", message: error.message }),
    });
    clientRef.current = client;
    client.connect();
    return () => { client.dispose(); clientRef.current = null; };
  }, []);

  useEffect(() => {
    if (scope !== "uid") return;
    fetch("/api/apps")
      .then((response) => { if (!response.ok) throw new Error(`App 清單讀取失敗 (${response.status})`); return response.json(); })
      .then((payload) => setApps(normalizeInstalledApps(payload)))
      .catch((error: unknown) => dispatch({ kind: "error", message: error instanceof Error ? error.message : "App 清單讀取失敗" }));
  }, [scope]);

  const send = (command: Record<string, unknown>, success = "指令已送出") => {
    if (!clientRef.current?.send(command)) setStatus("WebSocket 尚未連線");
    else setStatus(success);
  };

  const submit = (event: SubmitEvent) => {
    event.preventDefault();
    try {
      const resolvedTarget = scope === "uid" && packageName ? selectedPackageUid(apps, packageName) : targetId;
      send(createSyscallObserveCommand({ syscallNr: (document.getElementById("syscall-nr") as HTMLSelectElement)?.value, scope, targetId: resolvedTarget, phase, captureBacktrace, backtraceStackSize }), "觀察規則已新增");
    } catch (error) { dispatch({ kind: "error", message: error instanceof Error ? error.message : "規則無效" }); }
  };

  const observed = useMemo(() => observedSyscallsFromRules(state.rules), [state.rules]);
  const events = filterSyscallEvents(state);
  const catalog = state.supportedSyscalls.length ? state.supportedSyscalls : observed;

  return (
    <div className="app-root">
      <BrandHeader title="Arm64 Syscall Observe" status={<ConnectionPill status={state.connection} />} />
      {state.error && <div className="error-banner" role="alert">{state.error}</div>}
      <main className="workspace">
        <aside className="rules-pane" aria-label="Syscall observe controls">
          <section className="rule-editor">
            <SectionHeading title="新增觀察規則" count={state.rules.length} />
            <form className={`syscall-rule-form syscall-rule-form--${scope}`} onSubmit={submit as unknown as JSX.GenericEventHandler<HTMLFormElement>}>
              <Field label="Syscall"><select id="syscall-nr" required disabled={!catalog.length}>{catalog.map((entry) => <option key={entry.nr} value={entry.nr}>{entry.nr} · {entry.name}</option>)}</select></Field>
              <Field label="範圍"><select value={scope} onChange={(e) => setScope((e.currentTarget as HTMLSelectElement).value as Scope)}><option value="tgid">Process (TGID)</option><option value="tid">Thread (TID)</option><option value="uid">App (UID)</option></select></Field>
              {scope === "uid" ? <PackagePicker apps={apps} selected={packageName} query={packageQuery} open={packageMenuOpen} disabled={!apps.length} onFocus={() => setPackageMenuOpen(true)} onClose={() => setPackageMenuOpen(false)} onQuery={(value) => { setPackageQuery(value); setPackageName(""); setPackageMenuOpen(true); }} onSelect={(app) => { setPackageName(app.packageName); setPackageQuery(app.packageName); setPackageMenuOpen(false); }} /> : <Field label={scope === "tid" ? "Thread ID" : "Process ID"}><input type="number" min="1" value={targetId} onInput={(e) => setTargetId((e.currentTarget as HTMLInputElement).value)} required /></Field>}
              <fieldset className="phase-field"><legend>觀察階段</legend><div className="segmented-control">{(["enter", "exit", "both"] as const).map((item) => <label key={item}><input type="radio" checked={phase === item} onChange={() => setPhase(item)} /><span>{item}</span></label>)}</div></fieldset>
              <fieldset className="backtrace-field"><legend>Backtrace</legend><label className="backtrace-toggle"><input type="checkbox" checked={captureBacktrace} onChange={(e) => setCaptureBacktrace((e.currentTarget as HTMLInputElement).checked)} /><span>Capture user stack</span></label>{captureBacktrace && <Field label="Stack snapshot size"><input type="number" min="0" max="65536" step="8" value={backtraceStackSize} onInput={(e) => setBacktraceStackSize((e.currentTarget as HTMLInputElement).value)} /></Field>}<Field label="Unwind mode"><select value={backtraceMode} onChange={(e) => { const mode = normalizeBacktraceMode((e.currentTarget as HTMLSelectElement).value); setBacktraceMode(mode); send(createSyscallConfigCommand(mode), "Backtrace 模式已更新"); }}><option value="fp">Frame pointer (FP)</option><option value="dwarf">DWARF (libunwindstack)</option></select></Field></fieldset>
              <div className="form-actions"><Button type="submit" variant="primary">新增規則</Button></div>
            </form>
            {status && <p className="command-status">{status}</p>}
          </section>
          <section className="rule-list-section"><SectionHeading title="作用中的規則" count={state.rules.length} /><div className="rule-list">{state.rules.length === 0 ? <EmptyState>目前沒有作用中的規則</EmptyState> : state.rules.map((rule) => <RuleRow key={`${rule.syscallNr}:${rule.scope}:${rule.targetId}`} rule={rule} onRemove={() => send(createSyscallUnobserveCommand(rule), "規則已移除")} />)}</div></section>
        </aside>
        <section className="events-pane" aria-label="Syscall events">
          <div className="events-toolbar"><SectionHeading title="呼叫紀錄" count={events.length} /><div className="events-controls"><label className="event-syscall-filter"><span>顯示 Syscall</span><select value={state.selectedSyscallNr == null ? "" : String(state.selectedSyscallNr)} disabled={!observed.length} onChange={(e) => dispatch({ kind: "select", value: (e.currentTarget as HTMLSelectElement).value })}><option value="">選擇規則</option>{observed.map((entry) => <option key={entry.nr} value={entry.nr}>{entry.nr} · {entry.name}</option>)}</select></label><label className="event-search"><span>搜尋</span><input type="search" value={state.eventSearchQuery} onInput={(e) => dispatch({ kind: "search", value: (e.currentTarget as HTMLInputElement).value })} placeholder="名稱、PID、參數" /></label><span className={`listener-status ${state.listener.state === "failed" ? "listener-status--failed" : state.listener.state === "running" ? "listener-status--running" : "listener-status--stopped"}`}>{state.listener.error || state.listener.state}</span><Button onClick={() => send(createRestartListenerCommand(), "讀取器重啟中")}>重啟讀取器</Button><Button onClick={() => dispatch({ kind: "clear" })}>清除畫面</Button></div></div>
          <div className="event-list">{state.selectedSyscallNr == null ? <EmptyState>請先新增觀察規則</EmptyState> : events.length === 0 ? <EmptyState>尚未收到符合條件的呼叫紀錄</EmptyState> : events.map((event) => <SyscallEventCard key={`${event.displayId}:${event.seq}`} event={event} />)}</div>
        </section>
      </main>
    </div>
  );
}

function RuleRow({ rule, onRemove }: { rule: SyscallRule; onRemove: () => void }) {
  return <article className="rule-row"><div className="rule-summary"><span className="rule-name">{rule.syscallNr} · {rule.name || `syscall_${rule.syscallNr}`}</span><span className="rule-target">{rule.scope}:{rule.targetId} · {rule.phase}{rule.backtrace ? " · bt" : ""}</span></div><Button variant="danger" onClick={onRemove}>移除</Button></article>;
}

function SyscallEventCard({ event }: { event: SyscallEvent }) {
  const frames = event.backtraceFrames ?? [];
  const text = frames.map((frame) => `#${frame.index ?? 0} ${frame.label || frame.pc || "-"}`).join("\n");
  const phaseClass = event.afterDropped ? "phase-dropped" : event.phase === "complete" || event.phase === "exit" ? "phase-complete" : "phase-enter";
  return <details className={`event-row ${phaseClass}`}><summary><div className="event-summary"><div className="event-title-block"><div className="event-title">#{event.displayId ?? event.seq} · {event.syscallNr} {event.name || "syscall"}</div><div className="event-capture-preview">{event.phase} · tid={event.tid ?? "-"} · tgid={event.tgid ?? "-"} · {event.comm || "unknown"}</div></div><div className="event-meta"><span className={`event-phase ${event.phase === "exit" || event.phase === "complete" ? "event-phase--complete" : ""}`}>{event.phase}</span>{event.afterDropped && <span className="event-phase event-phase--dropped">dropped</span>}<CopyButton text={text} /></div></div></summary><div className="event-details"><div className="event-detail-grid"><Detail label="seq" value={event.seq} /><Detail label="callId" value={event.callId} /><Detail label="args" value={(event.args ?? []).join(" ")} /><Detail label="return" value={event.return} /></div>{frames.length > 0 && <section className="event-backtrace"><h3>Backtrace</h3><div className="backtrace-list">{frames.map((frame, index) => <div className="backtrace-frame" key={`${frame.index ?? index}:${frame.pc}`}><span className="backtrace-index">#{frame.index ?? index}</span><code className="backtrace-pc">{frame.pc || "-"}</code><span className="backtrace-label">{frame.label || "-"}</span><span className="backtrace-module">{frame.module || ""}</span></div>)}</div></section>}{event.captures && event.captures.length > 0 && <section><h3>Captures</h3><div className="event-detail-grid">{event.captures.map((capture, index) => <Detail key={index} label={`x${capture.argIndex ?? index} · ${capture.kind || "capture"}`} value={capture.value || capture.status} />)}</div></section>}</div></details>;
}

function Detail({ label, value }: { label: string; value: unknown }) { return <div className="event-detail"><span className="event-detail-label">{label}</span><span className="event-detail-value">{value == null ? "-" : String(value)}</span></div>; }
