import type { WireMessage } from "./protocol";
/** SSE retry, application heartbeat, and explicit page-exit release protect one speaker lease. */
export class RoomStream {
  readonly clientId = crypto.randomUUID();
  private source!: EventSource;
  private lastSeen = Date.now();
  private lastPing = 0;
  private watchdog = 0;
  private reconnect = 0;
  private closed = false;
  private pinging = false;
  private connectionGeneration = 0;
  constructor(
    private isLive: boolean,
    private onMessage: (message: WireMessage) => void,
    private onConnection: (connected: boolean) => void,
  ) {
    this.connect();
    this.watchdog = window.setInterval(() => {
      if (this.closed) return;
      if (Date.now() - this.lastSeen >= 8500) this.reset();
      else this.ping();
    }, 1500);
    window.addEventListener("offline", () => this.reset());
    window.addEventListener("online", () => this.reset());
    window.addEventListener(
      "pagehide",
      () => {
        this.closed = true;
        this.source.close();
        clearInterval(this.watchdog);
        clearTimeout(this.reconnect);
        navigator.sendBeacon(
          "/api/client/disconnect",
          new Blob([JSON.stringify({ clientId: this.clientId })], {
            type: "application/json",
          }),
        );
      },
      { once: true },
    );
  }
  private reset() {
    if (this.closed) return;
    this.connectionGeneration++;
    this.source.close();
    this.onConnection(false);
    this.lastSeen = Date.now();
    clearTimeout(this.reconnect);
    this.reconnect = window.setTimeout(() => this.connect(), 1000);
  }
  private ping() {
    if (this.pinging || this.closed || Date.now() - this.lastPing < 3000)
      return;
    this.lastPing = Date.now();
    this.pinging = true;
    const generation = this.connectionGeneration;
    void fetch("/api/client/heartbeat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ clientId: this.clientId }),
      signal: AbortSignal.timeout(3000),
    })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (generation === this.connectionGeneration && data && !data.connected) this.reset();
      })
      .catch(() => {})
      .finally(() => {
        this.pinging = false;
      });
  }
  private connect() {
    if (this.closed) return;
    this.lastSeen = Date.now();
    const generation = ++this.connectionGeneration;
    const source = new EventSource(
      `/api/events/stream?clientId=${this.clientId}&role=${this.isLive ? "live" : "preview"}`,
    );
    this.source = source;
    const current = () => !this.closed && generation === this.connectionGeneration && this.source === source;
    source.onopen = () => {
      if (!current()) return;
      this.lastSeen = Date.now();
      this.onConnection(true);
    };
    source.onerror = () => { if (current()) this.onConnection(false); };
    source.onmessage = (e) => {
      if (!current()) return;
      this.lastSeen = Date.now();
      this.ping();
      try {
        this.onMessage(JSON.parse(e.data) as WireMessage);
      } catch (error) {
        console.error("SSE message processing failed", error);
      }
    };
  }
}
