import { useEffect, useReducer, useRef, useState } from "preact/hooks";
import type { JSX } from "preact";
import { Button, BrandHeader, ConnectionPill, CopyButton, EmptyState, Field, SectionHeading, formatHex } from "../../shared/ui";
import { WebSocketClient } from "../../shared/transport";
import {
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
  setHwbpConnection,
  setHwbpError,
  visibleHwbpEvents,
  type BacktraceMode,
  type HwbpEvent,
  type HwbpFormValues,
  type HwbpState,
} from "./model";

type Action =
  | { kind: "message"; message: Record<string, unknown> }
  | { kind: "connection"; status: HwbpState["connection"] }
  | { kind: "error"; message: string | null }
  | { kind: "clear" };

function reducer(state: HwbpState, action: Action): HwbpState {
  if (action.kind === "message") return reduceHwbp(state, action.message);
  if (action.kind === "connection") return setHwbpConnection(state, action.status);
  if (action.kind === "error") return setHwbpError(state, action.message);
  return clearHwbpEvents(state);
}

const initialForm: HwbpFormValues = {
  processKind: "package",
  packageName: "",
  pid: "",
  targetKind: "abs",
  addr: "",
  lib: "",
  offset: "",
  mode: "R",
  len: "4",
  stackSize: "4096",
  backtraceMode: "fp",
  note: "",
};

export function HwbpView() {
  const [state, dispatch] = useReducer(reducer, undefined, initialHwbpState);
  const [form, setForm] = useState(initialForm);
  const [deduplicate, setDeduplicate] = useState(false);
  const [status, setStatus] = useState("");
  const clientRef = useRef<WebSocketClient | null>(null);

  useEffect(() => {
    const lengthSelect = document.querySelector<HTMLSelectElement>(
      ".rule-editor form .field-grid select",
    );
    if (!lengthSelect) return;
    lengthSelect.disabled = form.mode === "X";
    if (form.mode === "X") lengthSelect.value = "4";
  }, [form.mode, form.len]);

  useEffect(() => {
    const client = new WebSocketClient("/ws", {
      onStatus: (connection) => {
        dispatch({ kind: "connection", status: connection });
        setStatus(connection === "open" ? "已連線，等待事件" : connection === "closed" ? "連線中斷，將自動重試" : "");
      },
      onMessage: (message) => dispatch({ kind: "message", message }),
      onError: (error) => dispatch({ kind: "error", message: error.message }),
    });
    clientRef.current = client;
    client.connect();
    return () => { client.dispose(); clientRef.current = null; };
  }, []);

  const send = (command: Record<string, unknown>, success = "指令已送出") => {
    if (!clientRef.current?.send(command)) setStatus("WebSocket 尚未連線");
    else setStatus(success);
  };

  return (
    <div className="app-root">
      <BrandHeader title="HWBP Observe" status={<ConnectionPill status={state.connection} />} />
      {state.error && <div className="error-banner" role="alert">{state.error}</div>}
      <main className="workspace">
        <aside className="rules-pane" aria-label="HWBP observe controls">
          <HwbpEditor form={form} setForm={setForm} send={send} setStatus={setStatus} />
          <section className="rule-list-section">
            <SectionHeading title="作用中的觀察點" count={state.points.length} />
            <div className="rule-list">
            {state.points.length === 0 ? <EmptyState>目前沒有作用中的觀察點</EmptyState> : state.points.map((point) => (
                <article className="rule-row" key={`${point.pid}:${point.addr}`}>
                  <div className="rule-summary">
                    <span className="rule-name">{point.display || point.addr}</span>
                    <span className="rule-scope">{point.mode}/{point.len}</span>
                  </div>
                  <span className="rule-target">pid={point.pid}</span>
                  <Button variant="danger" onClick={() => send(buildHwbpUnobserveCommand(point), "觀察點已移除")}>移除</Button>
                </article>
              ))}
            </div>
          </section>
          <section className="rule-list-section">
            <SectionHeading title="已儲存的設定" count={state.savedConfigs.length} />
            <div className="rule-list">
              {state.savedConfigs.length === 0 ? <EmptyState>尚未儲存設定</EmptyState> : state.savedConfigs.map((config) => (
                <article className="rule-row" key={config.id}>
                  <div className="rule-summary">
                    <span className="rule-name">{config.id}</span>
                    <span className="rule-target">{config.display || config.addr || `${config.lib}+${config.offset}`}</span>
                  </div>
                  {config.note && <span className="rule-target">{config.note}</span>}
                  <div className="event-meta">
                    <Button onClick={() => send(buildHwbpApplyConfigCommand(config.id), "設定已套用")}>套用</Button>
                    <Button variant="danger" onClick={() => send(buildHwbpDeleteConfigCommand(config.id), "設定已刪除")}>刪除</Button>
                  </div>
                </article>
              ))}
            </div>
          </section>
        </aside>

        <section className="events-pane" aria-label="HWBP events">
          <div className="events-toolbar">
            <SectionHeading title="命中事件" count={visibleHwbpEvents({ ...state, deduplicateStacks: deduplicate }).length} />
            <div className="events-controls">
              <label className="backtrace-toggle"><input type="checkbox" checked={deduplicate} onChange={(event) => setDeduplicate((event.currentTarget as HTMLInputElement).checked)} /><span>去除重複 backtrace</span></label>
              <Button onClick={() => { dispatch({ kind: "clear" }); setStatus("畫面已清除"); }}>清除畫面</Button>
            </div>
          </div>
          {status && <p className="command-status">{status}</p>}
          <div className="event-list">
            {visibleHwbpEvents({ ...state, deduplicateStacks: deduplicate }).length === 0 ? <EmptyState>尚未收到硬體斷點事件</EmptyState> : visibleHwbpEvents({ ...state, deduplicateStacks: deduplicate }).map((event) => <HwbpEventCard key={String(event.displayId ?? event.seq)} event={event} />)}
          </div>
        </section>
      </main>
    </div>
  );
}

function HwbpEditor({ form, setForm, send, setStatus }: {
  form: HwbpFormValues;
  setForm: (next: HwbpFormValues | ((current: HwbpFormValues) => HwbpFormValues)) => void;
  send: (command: Record<string, unknown>, success?: string) => void;
  setStatus: (status: string) => void;
}) {
  const update = (patch: Partial<HwbpFormValues>) => setForm((current) => ({ ...current, ...patch }));
  const submit = (event: SubmitEvent) => {
    event.preventDefault();
    try {
      send(buildHwbpObserveCommand(form), "觀察已啟動");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "表單內容無效");
    }
  };
  const save = () => {
    const id = window.prompt("設定名稱");
    if (!id) return;
    try { send(buildHwbpSaveConfigCommand(form, id), "設定已儲存"); }
    catch (error) { setStatus(error instanceof Error ? error.message : "設定無效"); }
  };
  return (
    <section className="rule-editor">
      <SectionHeading title="新增觀察點" />
      <form onSubmit={submit as unknown as JSX.GenericEventHandler<HTMLFormElement>}>
        <fieldset>
          <legend>目標程序</legend>
          <div className="segmented-control segmented-control--two">
            {(["package", "pid"] as const).map((kind) => <label key={kind}><input type="radio" checked={form.processKind === kind} onChange={() => update({ processKind: kind })} /><span>{kind === "package" ? "Package" : "PID"}</span></label>)}
          </div>
        </fieldset>
        {form.processKind === "package" ? <Field label="App package"><input value={form.packageName} onInput={(e) => update({ packageName: (e.currentTarget as HTMLInputElement).value })} placeholder="com.example.app" /></Field> : <Field label="Target PID"><input type="number" min="1" value={form.pid} onInput={(e) => update({ pid: (e.currentTarget as HTMLInputElement).value })} /></Field>}
        <fieldset>
          <legend>位址來源</legend>
          <div className="segmented-control segmented-control--two">
            {(["abs", "lib"] as const).map((kind) => <label key={kind}><input type="radio" checked={form.targetKind === kind} onChange={() => update({ targetKind: kind })} /><span>{kind === "abs" ? "Absolute" : "Library + offset"}</span></label>)}
          </div>
        </fieldset>
        {form.targetKind === "abs" ? <Field label="Address"><input value={form.addr} onInput={(e) => update({ addr: (e.currentTarget as HTMLInputElement).value })} placeholder="0x7ff0001000" /></Field> : <div className="field-grid"><Field label="Library"><input value={form.lib} onInput={(e) => update({ lib: (e.currentTarget as HTMLInputElement).value })} placeholder="libfoo.so" /></Field><Field label="Offset"><input value={form.offset} onInput={(e) => update({ offset: (e.currentTarget as HTMLInputElement).value })} placeholder="0x1234" /></Field></div>}
        <fieldset>
          <legend>模式</legend>
          <div className="segmented-control segmented-control--four">{(["R", "W", "RW", "X"] as const).map((mode) => <label key={mode}><input type="radio" checked={form.mode === mode} onChange={() => update({ mode, len: mode === "X" ? "4" : form.len })} /><span>{mode}</span></label>)}</div>
        </fieldset>
        <div className="field-grid"><Field label="長度"><select value={form.len} onChange={(e) => update({ len: (e.currentTarget as HTMLSelectElement).value })}>{["1", "2", "4", "8"].map((len) => <option key={len}>{len}</option>)}</select></Field><Field label="Stack bytes"><input type="number" min="0" max="65536" value={form.stackSize} onInput={(e) => update({ stackSize: (e.currentTarget as HTMLInputElement).value })} /></Field></div>
        <Field label="Backtrace mode"><select value={form.backtraceMode} onChange={(e) => { const mode = (e.currentTarget as HTMLSelectElement).value as BacktraceMode; update({ backtraceMode: mode }); send(buildHwbpConfigCommand(mode), "Backtrace 模式已更新"); }}><option value="fp">Frame pointer (FP)</option><option value="dwarf">DWARF</option></select></Field>
        <Field label="設定備註"><textarea value={form.note} maxLength={2000} onInput={(e) => update({ note: (e.currentTarget as HTMLTextAreaElement).value })} placeholder="這個觀察點的用途" /></Field>
        <div className="form-actions"><Button type="button" onClick={save}>儲存設定</Button><Button type="submit" variant="primary">開始觀察</Button></div>
      </form>
    </section>
  );
}

function HwbpEventCard({ event }: { event: HwbpEvent }) {
  const backtrace = formatHwbpBacktrace(event);
  return <details className={`event-row ${Number(event.dropped) > 0 ? "phase-dropped" : "phase-enter"}`}><summary><div className="event-summary"><div className="event-title-block"><div className="event-title">#{event.displayId ?? event.seq} · {event.comm || "unknown"}</div><div className="event-capture-preview">tid={event.tid ?? "-"} · tgid={event.tgid ?? "-"} · pc={event.pc || "-"}</div></div><div className="event-meta"><span className="event-phase">bt:{event.backtraceMode || "fp"}</span>{Number(event.dropped) > 0 && <span className="event-phase event-phase--dropped">dropped {event.dropped}</span>}<CopyButton text={backtrace} /></div></div></summary><div className="event-details"><div className="event-detail-grid"><Detail label="watch" value={event.watchedAddr} /><Detail label="sp" value={event.sp} /><Detail label="pstate" value={event.pstate} /><Detail label="stack" value={`${event.stack?.copied ?? "-"} / ${event.stack?.requested ?? "-"}`} /></div><section className="event-backtrace"><h3>Backtrace</h3>{event.backtrace?.length ? <div className="backtrace-list">{event.backtrace.map((frame, index) => <div className="backtrace-frame" key={`${frame.index ?? index}:${frame.pc}`}><span className="backtrace-index">#{frame.index ?? index}</span><code className="backtrace-pc">{frame.pc || "-"}</code><span className="backtrace-label">{frame.label || "-"}</span><span className="backtrace-module">{frame.module || ""}</span></div>)}</div> : <EmptyState>No frames</EmptyState>}</section></div></details>;
}

function Detail({ label, value }: { label: string; value: unknown }) {
  return <div className="event-detail"><span className="event-detail-label">{label}</span><span className="event-detail-value">{value == null ? "-" : String(value)}</span></div>;
}
