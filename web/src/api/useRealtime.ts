import { useCallback, useEffect, useRef, useState } from "react";
import type { Client } from "./client";
import { Realtime, type SocketState } from "./realtime";
import type { ServerMessage } from "./types";

/**
 * Keeps a user's WebSocket open while `active`. The handlers are read through a ref, so they always see the screen's
 * latest state without reconnecting.
 */
export function useRealtime(
  client: Client,
  active: boolean,
  onMessage: (message: ServerMessage) => void,
  onConnect: () => void,
): { state: SocketState; send: (message: object) => boolean } {
  const [state, setState] = useState<SocketState>("closed");
  const handlers = useRef({ onMessage, onConnect });
  handlers.current = { onMessage, onConnect };
  const realtime = useRef<Realtime | undefined>(undefined);

  useEffect(() => {
    if (!active) return;
    const rt = new Realtime(client, {
      onMessage: (m) => handlers.current.onMessage(m),
      onConnect: () => handlers.current.onConnect(),
      onState: setState,
    });
    realtime.current = rt;
    rt.start();
    return () => {
      rt.stop();
      realtime.current = undefined;
      setState("closed");
    };
  }, [client, active]);

  const send = useCallback((message: object) => realtime.current?.send(message) ?? false, []);
  return { state, send };
}
