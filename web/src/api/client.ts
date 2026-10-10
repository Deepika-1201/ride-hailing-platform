import type { Driver, DriverStatus, GeoPoint, Offer, Quote, Ride, TimelineEntry, TokenResponse } from "./types";

/** An RFC 9457 problem the platform answered. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
  ) {
    super(`${status} ${code}${detail ? ": " + detail : ""}`);
  }
}

type Fetch = typeof fetch;

export interface ClientOptions {
  base?: string;
  fetch?: Fetch;
  sleep?: (ms: number) => Promise<void>;
  maxAttempts?: number;
  storage?: Pick<Storage, "getItem" | "setItem" | "removeItem">;
}

export interface Answer<T> {
  status: number;
  data?: T;
}

/**
 * The platform's REST API as a real app uses it (HLD §12.2): commands carry an Idempotency-Key and are retried with
 * the same key; the access token is refreshed a minute before it expires, or once after a 401.
 */
export class Client {
  private readonly base: string;
  private readonly fetch: Fetch;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly maxAttempts: number;
  private readonly storage?: ClientOptions["storage"];
  private phone = "";
  private access = "";
  private accessUntil = 0;
  private refreshToken = "";
  private refreshing?: Promise<void>;
  private signingIn?: Promise<void>;
  userId = "";
  roles: string[] = [];

  constructor(options: ClientOptions = {}) {
    this.base = options.base ?? "";
    this.fetch = options.fetch ?? fetch.bind(globalThis);
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.maxAttempts = options.maxAttempts ?? 5;
    try {
      this.storage = options.storage ?? globalThis.sessionStorage;
    } catch {
      this.storage = undefined;
    }
  }

  static newKey(): string {
    return "web-" + crypto.randomUUID();
  }

  get signedIn(): boolean {
    return this.refreshToken !== "";
  }

  /** Signs in with a one-time code; the local profile accepts a fixed one. */
  async signIn(phone: string, code = "123456"): Promise<void> {
    if (this.signingIn) {
      await this.signingIn;
      return this.signIn(phone, code);
    }
    if (this.phone === phone && this.signedIn) {
      await this.token(false);
      return;
    }
    this.phone = phone;
    this.signingIn = this.authenticate(phone, code);
    try {
      await this.signingIn;
    } finally {
      this.signingIn = undefined;
    }
  }

  private async authenticate(phone: string, code: string): Promise<void> {
    this.access = "";
    this.refreshToken = "";
    this.userId = "";
    this.roles = [];
    try {
      this.refreshToken = this.storage?.getItem(this.sessionKey()) ?? "";
    } catch {
      this.refreshToken = "";
    }
    if (this.refreshToken) {
      try {
        await this.token(true);
        return;
      } catch (error) {
        if (!(error instanceof ApiError) || (error.status !== 400 && error.status !== 401)) throw error;
        this.refreshToken = "";
        this.cacheRefreshToken();
      }
    }
    await this.call("POST", "/v1/auth/otp", { body: { phone }, auth: false });
    const tokens = await this.call<TokenResponse>("POST", "/v1/auth/token", { body: { phone, code }, auth: false });
    this.keep(tokens.data!);
  }

  private sessionKey(): string {
    return `ride-hailing:${this.base}:refresh:${this.phone}`;
  }

  private cacheRefreshToken(): void {
    try {
      if (this.refreshToken) this.storage?.setItem(this.sessionKey(), this.refreshToken);
      else this.storage?.removeItem(this.sessionKey());
    } catch {
      return;
    }
  }

  private keep(tokens: TokenResponse): void {
    this.access = tokens.access_token;
    this.accessUntil = Date.now() + tokens.expires_in * 1000;
    this.refreshToken = tokens.refresh_token;
    this.userId = tokens.user.id;
    this.roles = tokens.user.roles;
    this.cacheRefreshToken();
  }

  /** One rotation at a time: a rotated refresh token used again revokes the session. */
  private async token(force: boolean): Promise<string> {
    if (force || Date.now() > this.accessUntil - 60_000) {
      this.refreshing ??= (async () => {
        try {
          const answer = await this.call<TokenResponse>("POST", "/v1/auth/refresh", {
            body: { refresh_token: this.refreshToken },
            auth: false,
          });
          this.keep(answer.data!);
        } finally {
          this.refreshing = undefined;
        }
      })();
      await this.refreshing;
    }
    return this.access;
  }

  async call<T>(
    method: string,
    path: string,
    options: { body?: unknown; key?: string; auth?: boolean; retryCodes?: string[] } = {},
  ): Promise<Answer<T>> {
    const auth = options.auth ?? true;
    let delay = 250;
    let refreshed = false;
    let last: unknown;
    for (let attempt = 1; attempt <= this.maxAttempts; attempt++) {
      if (attempt > 1) {
        await this.sleep(delay / 2 + Math.random() * delay);
        delay = Math.min(delay * 2, 8000);
      }
      const headers: Record<string, string> = {};
      if (options.body !== undefined) headers["Content-Type"] = "application/json";
      if (options.key) headers["Idempotency-Key"] = options.key;
      if (auth) headers["Authorization"] = "Bearer " + (await this.token(false));
      let response: Response;
      try {
        response = await this.fetch(this.base + path, {
          method,
          headers,
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
        });
      } catch (error) {
        last = error;
        continue;
      }
      const text = await response.text();
      const data = text ? JSON.parse(text) : undefined;
      if (response.ok) {
        return { status: response.status, data: data as T };
      }
      const problem = new ApiError(response.status, data?.code ?? "UNKNOWN", data?.detail ?? data?.title ?? "");
      last = problem;
      if (response.status === 401 && auth && !refreshed) {
        refreshed = true;
        await this.token(true);
        delay = 0;
        continue;
      }
      const retryAfter = Number(response.headers.get("Retry-After"));
        if (response.status === 429 || response.status >= 500 || problem.code === "IDEMPOTENCY_KEY_IN_PROGRESS"
          || options.retryCodes?.includes(problem.code)) {
        if (retryAfter > 0) delay = Math.min(retryAfter, 30) * 1000;
        continue;
      }
      throw problem;
    }
    throw last instanceof Error ? last : new Error(String(last));
  }

  // Riders.
  quote(pickup: GeoPoint, dropoff: GeoPoint, category: string) {
    return this.call<Quote>("POST", "/v1/quotes", { body: { pickup, dropoff, category } }).then((a) => a.data!);
  }
  book(quoteId: string, key: string) {
    return this.call<Ride>("POST", "/v1/rides", { body: { quote_id: quoteId }, key }).then((a) => a.data!);
  }
  ride(id: string) {
    return this.call<Ride>("GET", `/v1/rides/${id}`).then((a) => a.data!);
  }
  riderActiveRide() {
    return this.call<Ride>("GET", "/v1/riders/me/active-ride").then((a) => a.data);
  }
  rideCommand(id: string, command: string, key: string, body?: unknown) {
    return this.call<Ride>("POST", `/v1/rides/${id}/${command}`, { body, key }).then((a) => a.data!);
  }
  rate(id: string, stars: number, key: string) {
    return this.call("POST", `/v1/rides/${id}/rating`, { body: { stars }, key, retryCodes: ["RATING_NOT_OPEN"] });
  }

  // Drivers.
  driver() {
    return this.call<Driver>("GET", "/v1/drivers/me").then((a) => a.data!);
  }
  goOnline(vehicleId: string, key: string) {
    return this.call<DriverStatus>("POST", "/v1/drivers/me/online", { body: { vehicle_id: vehicleId }, key }).then(
      (a) => a.data!,
    );
  }
  goOffline(key: string) {
    return this.call<DriverStatus>("POST", "/v1/drivers/me/offline", { key }).then((a) => a.data!);
  }
  currentOffer() {
    return this.call<Offer>("GET", "/v1/drivers/me/offer").then((a) => a.data);
  }
  driverActiveRide() {
    return this.call<Ride>("GET", "/v1/drivers/me/active-ride").then((a) => a.data);
  }
  accept(offerId: string, key: string) {
    return this.call<Ride>("POST", `/v1/offers/${offerId}/accept`, { key }).then((a) => a.data!);
  }
  decline(offerId: string, key: string) {
    return this.call("POST", `/v1/offers/${offerId}/decline`, { key });
  }

  // Operations.
  opsRides(statuses: string[], cityId: string) {
    const query = new URLSearchParams({ status: statuses.join(","), city_id: cityId, limit: "100" });
    return this.call<{ items: Ride[] }>("GET", `/v1/ops/rides?${query}`).then((a) => a.data!.items);
  }
  timeline(rideId: string) {
    return this.call<{ entries: TimelineEntry[] }>("GET", `/v1/ops/rides/${rideId}/timeline`).then(
      (a) => a.data!.entries,
    );
  }

  ticket() {
    return this.call<{ ticket: string; url: string }>("POST", "/v1/realtime/tickets").then((a) => a.data!);
  }
}
