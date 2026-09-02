export type ConnectionStatus = "connecting" | "open" | "closed" | "error";

export interface WebSocketClientOptions {
  token?: string | null;
  reconnectDelayMs?: number;
  onStatus?: (status: ConnectionStatus) => void;
  onMessage?: (message: Record<string, unknown>) => void;
  onError?: (error: Error) => void;
}

/** Small browser-only transport shared by both observe features. */
export class WebSocketClient {
  private socket: WebSocket | null = null;
  private reconnectTimer: number | null = null;
  private disposed = false;
  private readonly delay: number;

  constructor(
    private readonly path: string,
    private readonly options: WebSocketClientOptions = {},
  ) {
    this.delay = options.reconnectDelayMs ?? 3000;
  }

  connect(): void {
    this.disposed = false;
    this.open();
  }

  send(command: Record<string, unknown>): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(command));
    return true;
  }

  dispose(): void {
    this.disposed = true;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.socket = null;
  }

  private open(): void {
    if (this.disposed) return;
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return;
    }

    this.options.onStatus?.("connecting");
    const params = new URLSearchParams();
    if (this.options.token) params.set("token", this.options.token);
    const query = params.toString();
    const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
    const url = `${scheme}//${window.location.host}${this.path}${query ? `?${query}` : ""}`;
    const socket = new WebSocket(url);
    this.socket = socket;

    socket.addEventListener("open", () => this.options.onStatus?.("open"));
    socket.addEventListener("message", (event) => {
      try {
        const parsed = JSON.parse(String(event.data));
        if (parsed && typeof parsed === "object") {
          this.options.onMessage?.(parsed as Record<string, unknown>);
        }
      } catch (error) {
        this.options.onError?.(error instanceof Error ? error : new Error("Invalid server message"));
      }
    });
    socket.addEventListener("error", () => this.options.onStatus?.("error"));
    socket.addEventListener("close", () => {
      if (this.socket === socket) this.socket = null;
      if (!this.disposed) this.scheduleReconnect();
      this.options.onStatus?.("closed");
    });
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null || this.disposed) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, this.delay);
  }
}

export function tokenFromLocation(): string | null {
  return new URLSearchParams(window.location.search).get("token");
}
