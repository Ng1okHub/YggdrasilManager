import type { ComponentChildren, JSX } from "preact";
import { useState } from "preact/hooks";

export function BrandHeader({ title, subtitle, status }: {
  title: string;
  subtitle?: string;
  status: JSX.Element;
}) {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 17l6-6-6-6" />
            <path d="M12 19h8" />
          </svg>
        </span>
        <div>
          <p className="eyebrow">Yggdrasil</p>
          <h1>{title}</h1>
          {subtitle && <p className="brand-subtitle">{subtitle}</p>}
        </div>
      </div>
      {status}
    </header>
  );
}

export function ConnectionPill({ status }: { status: string }) {
  const label: Record<string, string> = {
    connecting: "正在連線",
    open: "已連線",
    closed: "等待重連",
    error: "連線錯誤",
  };
  const className = status === "open" ? "connected" : status === "closed" ? "offline" : status === "error" ? "offline" : "pending";
  return <div className={`connection connection--${className}`} role="status" aria-live="polite">{label[status] ?? status}</div>;
}

export function SectionHeading({ title, count }: { title: string; count?: number }) {
  return <div className="section-heading"><h2>{title}</h2>{count !== undefined && <span className="count">{count}</span>}</div>;
}

export function Button(props: JSX.HTMLAttributes<HTMLButtonElement> & { children: ComponentChildren; variant?: "primary" | "quiet" | "danger"; type?: "button" | "submit" | "reset" }) {
  const { variant = "quiet", className = "", children, ...rest } = props;
  return <button className={`button button--${variant} ${className}`} {...rest}>{children}</button>;
}

export function EmptyState({ children }: { children: ComponentChildren }) {
  return <p className="empty-state">{children}</p>;
}

export function Field({ label, children, className = "" }: { label: string; children: ComponentChildren; className?: string }) {
  return <label className={`field ${className}`}><span>{label}</span>{children}</label>;
}

export function formatHex(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return `0x${value.toString(16)}`;
  return "-";
}

export async function copyText(text: string): Promise<boolean> {
  if (typeof navigator !== "undefined" && typeof navigator.clipboard?.writeText === "function") {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // LAN deployments are commonly served over HTTP where Clipboard API
      // permissions are unavailable. Fall back to a temporary selection.
    }
  }

  if (typeof document === "undefined" || !document.body || typeof document.execCommand !== "function") return false;
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.setAttribute("aria-hidden", "true");
  Object.assign(textarea.style, { position: "fixed", opacity: "0", pointerEvents: "none" });
  document.body.append(textarea);
  textarea.focus();
  textarea.select();
  try {
    return document.execCommand("copy");
  } finally {
    textarea.remove();
  }
}

export function CopyButton({ text }: { text: string }) {
  const [feedback, setFeedback] = useState<"idle" | "copied" | "failed">("idle");

  const handleClick = (event: JSX.TargetedMouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    void copyText(text)
      .then((ok) => {
        setFeedback(ok ? "copied" : "failed");
        window.setTimeout(() => setFeedback("idle"), 1800);
      })
      .catch(() => {
        setFeedback("failed");
        window.setTimeout(() => setFeedback("idle"), 1800);
      });
  };

  return (
    <Button type="button" onClick={handleClick} aria-live="polite">
      {feedback === "copied" ? "已複製" : feedback === "failed" ? "複製失敗" : "複製 stack"}
    </Button>
  );
}
