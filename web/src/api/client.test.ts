import { describe, expect, it } from "vitest";
import { ApiError, Client, type ClientOptions } from "./client";

interface Seen {
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: Record<string, unknown>;
}

interface Reply {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
}

/** A platform that signs anyone in and answers everything else with `handle`. */
function platform(handle: (seen: Seen) => Reply, accessSeconds = 900, storage?: ClientOptions["storage"]) {
  const calls: Seen[] = [];
  let issued = 0;
  const tokens = (): Reply => {
    issued++;
    return {
      status: 200,
      body: {
        access_token: `access-${issued}`,
        token_type: "Bearer",
        expires_in: accessSeconds,
        refresh_token: `refresh-${issued}-xxxxxxxxxxxxxxxxxxxx`,
        refresh_expires_in: 2_592_000,
        user: { id: "rider-1", roles: ["RIDER"] },
      },
    };
  };
  const fetch = async (url: string | URL | Request, init?: RequestInit) => {
    const seen: Seen = {
      method: init?.method ?? "GET",
      path: String(url),
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(seen);
    const reply =
      seen.path === "/v1/auth/otp"
        ? { status: 202, body: { expires_at: "2026-10-10T10:00:00Z", resend_after_s: 30 } }
        : seen.path === "/v1/auth/token" || seen.path === "/v1/auth/refresh"
          ? tokens()
          : handle(seen);
    return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
      status: reply.status,
      headers: reply.headers,
    });
  };
  const sleeps: number[] = [];
  const options: ClientOptions = {
    fetch: fetch as typeof globalThis.fetch,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    storage,
  };
  const client = new Client(options);
  return { client, options, calls, sleeps, apiCalls: () => calls.filter((c) => !c.path.startsWith("/v1/auth/")) };
}

function sessionStore(): NonNullable<ClientOptions["storage"]> {
  const tokens = new Map<string, string>();
  return {
    getItem: (key) => tokens.get(key) ?? null,
    setItem: (key, value) => { tokens.set(key, value); },
    removeItem: (key) => { tokens.delete(key); },
  };
}

const problem = (status: number, code: string, headers?: Record<string, string>): Reply => ({
  status,
  body: { status, code, title: code, request_id: "r" },
  headers,
});

describe("Client", () => {
  it("requests one code for concurrent sign-ins as the same user", async () => {
    const platformApi = platform(() => ({ status: 204 }));

    await Promise.all([
      platformApi.client.signIn("+919000000001"),
      platformApi.client.signIn("+919000000001"),
    ]);

    expect(platformApi.calls.filter((call) => call.path === "/v1/auth/otp")).toHaveLength(1);
    expect(platformApi.calls.filter((call) => call.path === "/v1/auth/token")).toHaveLength(1);
  });

  it("resumes a tab session and stores the rotated refresh token without requesting another code", async () => {
    const storage = sessionStore();
    const platformApi = platform(() => ({ status: 204 }), 900, storage);
    await platformApi.client.signIn("+919000000001");
    const reloaded = new Client(platformApi.options);

    await reloaded.signIn("+919000000001");

    expect(platformApi.calls.filter((call) => call.path === "/v1/auth/otp")).toHaveLength(1);
    const refreshes = platformApi.calls.filter((call) => call.path === "/v1/auth/refresh");
    expect(refreshes).toHaveLength(1);
    expect(refreshes[0].body?.refresh_token).toBe("refresh-1-xxxxxxxxxxxxxxxxxxxx");
    expect(storage.getItem("ride-hailing::refresh:+919000000001")).toBe("refresh-2-xxxxxxxxxxxxxxxxxxxx");
    expect(reloaded.userId).toBe("rider-1");
  });

  it("requests a new code when the cached session has been revoked", async () => {
    const storage = sessionStore();
    const platformApi = platform(() => ({ status: 204 }), 900, storage);
    await platformApi.client.signIn("+919000000001");
    const reloaded = new Client({
      ...platformApi.options,
      fetch: async (url, init) => String(url) === "/v1/auth/refresh"
        ? new Response(JSON.stringify({ code: "INVALID_TOKEN" }), { status: 401 })
        : platformApi.options.fetch!(url, init),
    });

    await reloaded.signIn("+919000000001");

    expect(platformApi.calls.filter((call) => call.path === "/v1/auth/otp")).toHaveLength(2);
    expect(storage.getItem("ride-hailing::refresh:+919000000001")).toBe("refresh-2-xxxxxxxxxxxxxxxxxxxx");
  });

  it("preserves the cached session and does not request a code during a refresh outage", async () => {
    const storage = sessionStore();
    const platformApi = platform(() => ({ status: 204 }), 900, storage);
    await platformApi.client.signIn("+919000000001");
    const reloaded = new Client({
      ...platformApi.options,
      maxAttempts: 1,
      fetch: async (url, init) => String(url) === "/v1/auth/refresh"
        ? new Response(JSON.stringify({ code: "SERVICE_UNAVAILABLE" }), { status: 503 })
        : platformApi.options.fetch!(url, init),
    });

    await expect(reloaded.signIn("+919000000001")).rejects.toMatchObject({ status: 503 });

    expect(platformApi.calls.filter((call) => call.path === "/v1/auth/otp")).toHaveLength(1);
    expect(storage.getItem("ride-hailing::refresh:+919000000001")).toBe("refresh-1-xxxxxxxxxxxxxxxxxxxx");
  });

  it("retries a command with its idempotency key until it succeeds", async () => {
    const replies = [problem(503, "SERVICE_UNAVAILABLE"), problem(409, "IDEMPOTENCY_KEY_IN_PROGRESS")];
    const p = platform(() => replies.shift() ?? { status: 200, body: { id: "ride-1", status: "DRIVER_ASSIGNED", version: 1 } });
    await p.client.signIn("+917000000001");

    const ride = await p.client.accept("offer-1", "key-1");

    expect(ride.status).toBe("DRIVER_ASSIGNED");
    expect(p.apiCalls().map((c) => c.headers["Idempotency-Key"])).toEqual(["key-1", "key-1", "key-1"]);
  });

  it("doesn't retry an answer that won't change", async () => {
    const p = platform(() => problem(409, "OFFER_NO_LONGER_AVAILABLE"));
    await p.client.signIn("+917000000001");

    const error = await p.client.accept("offer-1", "key-1").catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("OFFER_NO_LONGER_AVAILABLE");
    expect(p.apiCalls()).toHaveLength(1);
  });

  it("rotates the refresh token once after a 401 and retries", async () => {
    let first = true;
    const p = platform(() => {
      if (first) {
        first = false;
        return problem(401, "TOKEN_EXPIRED");
      }
      return { status: 200, body: { id: "d", first_name: "Ravi", vehicles: [], status: { status: "OFFLINE", version: 0 } } };
    });
    await p.client.signIn("+917000000001");

    await p.client.driver();

    expect(p.apiCalls().map((c) => c.headers["Authorization"])).toEqual(["Bearer access-1", "Bearer access-2"]);
    expect(p.calls.filter((c) => c.path === "/v1/auth/refresh")).toHaveLength(1);
  });

  it("refreshes a token about to expire once for calls made together", async () => {
    const p = platform(() => ({ status: 200, body: { id: "ride-1" } }), 30);
    await p.client.signIn("+917000000001");

    await Promise.all([p.client.ride("ride-1"), p.client.ride("ride-1"), p.client.ride("ride-1")]);

    const refreshes = p.calls.filter((c) => c.path === "/v1/auth/refresh");
    expect(refreshes).toHaveLength(1);
    expect(refreshes[0].body?.refresh_token).toBe("refresh-1-xxxxxxxxxxxxxxxxxxxx");
  });

  it("waits as long as Retry-After asks before retrying", async () => {
    const replies = [problem(429, "RATE_LIMITED", { "Retry-After": "2" })];
    const p = platform(() => replies.shift() ?? { status: 201, body: { id: "q-1" } });
    await p.client.signIn("+918000009001");

    await p.client.quote({ lat: 12.97, lon: 77.6 }, { lat: 12.93, lon: 77.62 }, "MINI");

    expect(p.sleeps).toHaveLength(1);
    expect(p.sleeps[0]).toBeGreaterThanOrEqual(1000);
    expect(p.sleeps[0]).toBeLessThanOrEqual(3000);
  });

  it("answers nothing for an empty 204", async () => {
    const p = platform(() => ({ status: 204 }));
    await p.client.signIn("+917000000001");

    expect(await p.client.currentOffer()).toBeUndefined();
  });

  it("retries a rating with the same key while the completed-trip projection catches up", async () => {
    const replies = [problem(409, "RATING_NOT_OPEN"), problem(409, "RATING_NOT_OPEN")];
    const platformApi = platform(() => replies.shift() ?? { status: 201, body: { stars: 5 } });
    await platformApi.client.signIn("+918000009002");

    await platformApi.client.rate("ride-1", 5, "rating-key");

    expect(platformApi.apiCalls()).toHaveLength(3);
    expect(platformApi.apiCalls().map((call) => call.headers["Idempotency-Key"]))
      .toEqual(["rating-key", "rating-key", "rating-key"]);
    expect(platformApi.apiCalls().map((call) => call.body)).toEqual([{ stars: 5 }, { stars: 5 }, { stars: 5 }]);
  });

  it("does not hide a rating that remains unavailable after the retry budget", async () => {
    const platformApi = platform(() => problem(409, "RATING_NOT_OPEN"));
    await platformApi.client.signIn("+918000009002");

    await expect(platformApi.client.rate("ride-1", 5, "rating-key"))
      .rejects.toMatchObject({ status: 409, code: "RATING_NOT_OPEN" });

    expect(platformApi.apiCalls()).toHaveLength(5);
  });
});
