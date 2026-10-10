import type { Client } from "./client";
import type { ServerMessage } from "./types";

export type SocketState = "connecting" | "open" | "closed";

export interface RealtimeHandlers {
  onMessage: (message: ServerMessage) => void;
  /** After every connect: resync over HTTPS, since pushes are best effort (ADR-006). */
  onConnect?: () => void;
  onState?: (state: SocketState) => void;
}

export interface RealtimeOptions {
  /** The WebSocket URL; by default /ws on the page's own origin, where nginx or the dev server proxies it. */
  url?: string;
  WebSocket?: typeof WebSocket;
  random?: () => number;
}

/** One user's WebSocket: a ticket per connect, reconnects with backoff and jitter, and the server's drain notice. */
export class Realtime {
  private socket?: WebSocket;
  private stopped = true;
  private backoff = 1000;
  private timer?: ReturnType<typeof setTimeout>;
  private readonly url: string;
  private readonly Socket: typeof WebSocket;
  private readonly random: () => number;

  constructor(
    private readonly client: Client,
    private readonly handlers: RealtimeHandlers,
    options: RealtimeOptions = {},
  ) {
    this.url = options.url ?? sameOrigin();
    this.Socket = options.WebSocket ?? WebSocket;
    this.random = options.random ?? Math.random;
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    void this.connect();
  }

  stop(): void {
    this.stopped = true;
    clearTimeout(this.timer);
    this.socket?.close();
    this.socket = undefined;
  }

  /** Sends a client message; false while the socket is down. */
  send(message: object): boolean {
    if (this.socket?.readyState !== 1) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  private async connect(): Promise<void> {
    this.handlers.onState?.("connecting");
    let ticket: string;
    try {
      ticket = (await this.client.ticket()).ticket;
    } catch {
      this.retry();
      return;
    }
    if (this.stopped) return;
    const socket = new this.Socket(`${this.url}?ticket=${encodeURIComponent(ticket)}`);
    this.socket = socket;
    socket.onopen = () => {
      this.backoff = 1000;
      this.handlers.onState?.("open");
      this.handlers.onConnect?.();
    };
    socket.onmessage = (event: MessageEvent) => {
      let message: ServerMessage;
      try {
        message = JSON.parse(String(event.data));
      } catch {
        return;
      }
      if (message.type === "reconnect") {
        // The node is draining (NFR-12): move after the delay it gives, which spreads clients over the drain.
        setTimeout(() => socket.close(), message.after_ms);
      }
      this.handlers.onMessage(message);
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      this.handlers.onState?.("closed");
      this.retry();
    };
  }

  private retry(): void {
    if (this.stopped) return;
    const wait = this.backoff / 2 + this.random() * this.backoff;
    this.backoff = Math.min(this.backoff * 2, 30_000);
    this.timer = setTimeout(() => void this.connect(), wait);
  }
}

function sameOrigin(): string {
  const { protocol, host } = window.location;
  return `${protocol === "https:" ? "wss" : "ws"}://${host}/ws`;
}
