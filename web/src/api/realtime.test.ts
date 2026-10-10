import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Client } from "./client";
import { Realtime } from "./realtime";
import type { ServerMessage } from "./types";

class FakeSocket {
  static made: FakeSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onopen?: () => void;
  onmessage?: (event: { data: string }) => void;
  onclose?: () => void;

  constructor(readonly url: string) {
    FakeSocket.made.push(this);
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  receive(message: object) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.onclose?.();
  }
}

function setUp() {
  let tickets = 0;
  const client = { ticket: async () => ({ ticket: `t${++tickets}`, url: "ws://elsewhere/ws" }) } as unknown as Client;
  const received: ServerMessage[] = [];
  let connects = 0;
  const realtime = new Realtime(
    client,
    { onMessage: (m) => received.push(m), onConnect: () => connects++ },
    { url: "ws://localhost:5173/ws", WebSocket: FakeSocket as unknown as typeof WebSocket, random: () => 0 },
  );
  return { realtime, received, connects: () => connects };
}

describe("Realtime", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.made = [];
  });
  afterEach(() => vi.useRealTimers());

  it("connects with a fresh ticket each time and resyncs after every connect", async () => {
    const { realtime, connects } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.made[0].url).toBe("ws://localhost:5173/ws?ticket=t1");
    FakeSocket.made[0].open();

    FakeSocket.made[0].close();
    await vi.advanceTimersByTimeAsync(500);

    expect(FakeSocket.made).toHaveLength(2);
    expect(FakeSocket.made[1].url).toBe("ws://localhost:5173/ws?ticket=t2");
    FakeSocket.made[1].open();
    expect(connects()).toBe(2);
    realtime.stop();
  });

  it("backs off further after each failed connect", async () => {
    const { realtime } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.made[0].close();
    await vi.advanceTimersByTimeAsync(500);
    FakeSocket.made[1].close();

    await vi.advanceTimersByTimeAsync(999);
    expect(FakeSocket.made).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeSocket.made).toHaveLength(3);
    realtime.stop();
  });

  it("leaves a draining node after the delay it gives", async () => {
    const { realtime, received } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);
    const socket = FakeSocket.made[0];
    socket.open();

    socket.receive({ type: "reconnect", after_ms: 3000 });
    await vi.advanceTimersByTimeAsync(2999);
    expect(socket.readyState).toBe(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(socket.readyState).toBe(3);
    expect(received.map((m) => m.type)).toEqual(["reconnect"]);
    realtime.stop();
  });

  it("refuses to send while the socket is down", async () => {
    const { realtime } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);

    expect(realtime.send({ type: "offer_seen", offer_id: "o1" })).toBe(false);
    FakeSocket.made[0].open();
    expect(realtime.send({ type: "offer_seen", offer_id: "o1" })).toBe(true);
    expect(FakeSocket.made[0].sent).toEqual(['{"type":"offer_seen","offer_id":"o1"}']);
    realtime.stop();
  });

  it("stops reconnecting once stopped", async () => {
    const { realtime } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.made[0].open();

    realtime.stop();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(FakeSocket.made).toHaveLength(1);
  });

  it("ignores frames that aren't JSON", async () => {
    const { realtime, received } = setUp();
    realtime.start();
    await vi.advanceTimersByTimeAsync(0);
    FakeSocket.made[0].open();

    FakeSocket.made[0].onmessage?.({ data: "not json" });
    FakeSocket.made[0].receive({ type: "offer_withdrawn", offer_id: "o1", reason: "EXPIRED" });

    expect(received).toEqual([{ type: "offer_withdrawn", offer_id: "o1", reason: "EXPIRED" }]);
    realtime.stop();
  });
});
