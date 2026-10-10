import { useEffect, useMemo, useRef, useState } from "react";
import { ApiError, Client } from "../api/client";
import { type DriverStatus, type GeoPoint, type Offer, type Ride, type Vehicle, isEnded, rupees } from "../api/types";
import { useRealtime } from "../api/useRealtime";
import { distanceM, drive, driverPhone } from "../geo";
import { type Marker, markerFeatures } from "../map/layers";
import { BENGALURU, MapView } from "../map/MapView";

const TICK_S = 4;
const SPEED_MPS = 30 / 3.6;
const ARRIVE_WITHIN_M = 50;

type ShownOffer = Offer & { deadline: number };

/** FR-S6: a person as one driver: go online, take an offer, drive to the pickup, start with the PIN, complete. */
export function Driver() {
  const client = useMemo(() => new Client(), []);
  const [number, setNumber] = useState(1993);
  const [signedIn, setSignedIn] = useState(false);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [vehicle, setVehicle] = useState<Vehicle>();
  const [status, setStatus] = useState<DriverStatus>();
  const [at, setAt] = useState<GeoPoint>(BENGALURU);
  const [target, setTarget] = useState<GeoPoint>();
  const [offer, setOffer] = useState<ShownOffer>();
  const [ride, setRide] = useState<Ride>();
  const [pin, setPin] = useState("");
  const [now, setNow] = useState(Date.now());
  const seq = useRef(0);

  const run = async (what: string, action: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    try {
      await action();
    } catch (e) {
      setError(`${what}: ${e instanceof ApiError ? `${e.code} ${e.detail}` : String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const keepStatus = (next: DriverStatus) => setStatus((s) => (!s || next.version >= s.version ? next : s));
  const keepRide = (next: Ride) =>
    setRide((current) => (!current || current.id !== next.id || next.version >= current.version ? next : current));
  const show = (o: Offer, send: (m: object) => boolean) => {
    setOffer({ ...o, deadline: Date.now() + (o.expires_in_ms ?? 0) });
    send({ type: "offer_seen", offer_id: o.id });
  };

  const { state: socket, send } = useRealtime(
    client,
    signedIn,
    (m) => {
      switch (m.type) {
        case "offer":
          show({ ...m, id: m.offer_id }, send);
          break;
        case "offer_withdrawn":
          setOffer((o) => (o?.id === m.offer_id ? undefined : o));
          if (offer?.id === m.offer_id) setError(`The offer ended: ${m.reason.toLowerCase().replace("_", " ")}`);
          break;
        case "driver_status":
          void client
            .driver()
            .then((d) => keepStatus(d.status))
            .catch(() => undefined);
          if (m.status === "OFFLINE") setOffer(undefined);
          break;
        case "ride_status":
          if (!ride || ride.id === m.ride_id) {
            void client
              .ride(m.ride_id)
              .then(async (r) => {
                keepRide(r);
                if (isEnded(r.status)) keepStatus((await client.driver()).status);
              })
              .catch(() => undefined);
          }
          break;
      }
    },
    // Pushes are best effort: after every connect, read the status, the offer and the ride again.
    () => {
      void (async () => {
        keepStatus((await client.driver()).status);
        const pending = await client.currentOffer();
        if (pending) show(pending, send);
        const active = await client.driverActiveRide();
        if (active) keepRide(active);
      })().catch(() => undefined);
    },
  );

  const online = status !== undefined && status.status !== "OFFLINE";
  const goal = ride?.status === "DRIVER_ASSIGNED" ? ride.pickup : ride?.status === "IN_TRIP" ? ride.dropoff : target;
  const latest = useRef({ at, goal, online });
  latest.current = { at, goal, online };

  // The app's location every 4 s while online, over the WebSocket (LLD §9.1).
  useEffect(() => {
    if (!signedIn) return;
    const timer = setInterval(() => {
      const { at: from, goal: to, online: on } = latest.current;
      const step = drive(from, to, TICK_S, SPEED_MPS);
      setAt(step.at);
      if (!on) return;
      seq.current = Math.max(seq.current + 1, Date.now());
      send({
        type: "location",
        seq: seq.current,
        lat: step.at.lat,
        lon: step.at.lon,
        accuracy_m: 8,
        ...(step.heading_deg === undefined ? {} : { heading_deg: step.heading_deg }),
        speed_mps: step.speed_mps,
        device_time: new Date().toISOString(),
      });
    }, TICK_S * 1000);
    return () => clearInterval(timer);
  }, [signedIn, send]);

  useEffect(() => {
    if (!offer) return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [offer]);

  const signIn = () =>
    run("Signing in", async () => {
      await client.signIn(driverPhone(number));
      const me = await client.driver();
      setVehicle(me.vehicles[0]);
      setStatus(me.status);
      setSignedIn(true);
    });

  const goOnline = () => run("Going online", async () => keepStatus(await client.goOnline(vehicle!.id, Client.newKey())));
  const goOffline = () => run("Going offline", async () => keepStatus(await client.goOffline(Client.newKey())));

  const accept = () =>
    run("Accepting", async () => {
      const id = offer!.id;
      setOffer(undefined);
      keepRide(await client.accept(id, Client.newKey()));
      keepStatus((await client.driver()).status);
    });
  const decline = () =>
    run("Declining", async () => {
      const id = offer!.id;
      setOffer(undefined);
      await client.decline(id, Client.newKey());
    });

  const command = (name: string, body?: unknown) =>
    run(name, async () => {
      keepRide(await client.rideCommand(ride!.id, name, Client.newKey(), body));
      if (name === "start") setPin("");
      keepStatus((await client.driver()).status);
    });

  const onMapClick = (p: GeoPoint) => {
    if (!online) setAt(p);
    else if (!ride || isEnded(ride.status)) setTarget(p);
  };

  const markers = useMemo(() => {
    const list: Marker[] = [{ at, kind: "me" }];
    const trip = ride && !isEnded(ride.status) ? ride : offer;
    if (trip) list.push({ at: trip.pickup, kind: "pickup" }, { at: trip.dropoff, kind: "dropoff" });
    else if (target && online) list.push({ at: target, kind: "driver" });
    return markerFeatures(list);
  }, [at, ride, offer, target, online]);

  if (!signedIn) {
    return (
      <div className="screen">
        <aside className="panel">
          <h2>Driver</h2>
          {error && <p className="error">{error}</p>}
          <label>
            Driver number <input type="number" min={1} max={2000} value={number} onChange={(e) => setNumber(Number(e.target.value))} />
          </label>
          <p className="muted">{driverPhone(number)}</p>
          <button disabled={busy} onClick={signIn}>
            Sign in
          </button>
        </aside>
        <MapView />
      </div>
    );
  }

  const toPickup = ride ? Math.round(distanceM(at, ride.pickup)) : 0;
  const toDropoff = ride ? Math.round(distanceM(at, ride.dropoff)) : 0;
  const active = ride && !isEnded(ride.status);
  return (
    <div className="screen">
      <aside className="panel">
        <h2>Driver {number}</h2>
        <p className="muted">
          Live: {socket} · {status?.status ?? "…"}
          {vehicle && (
            <>
              {" "}
              · {vehicle.category} {vehicle.plate}
            </>
          )}
        </p>
        {error && <p className="error">{error}</p>}
        {!online && (
          <>
            <p className="muted">Position {at.lat.toFixed(5)}, {at.lon.toFixed(5)}</p>
            <button disabled={busy || !vehicle} onClick={goOnline}>
              Go online
            </button>
          </>
        )}
        {online && !active && !offer && (
          <>
            <p>Waiting for an offer.</p>
            <button disabled={busy} onClick={goOffline}>
              Go offline
            </button>
          </>
        )}
        {offer && (
          <div className="card offer">
            <p>
              <b>New ride</b> · {rupees(offer.fare)} · pickup {(offer.pickup_distance_m / 1000).toFixed(1)} km away
            </p>
            <p className="muted">{Math.max(0, Math.ceil((offer.deadline - now) / 1000))} s to answer</p>
            <button disabled={busy} onClick={accept}>
              Accept
            </button>
            <button disabled={busy} onClick={decline}>
              Decline
            </button>
          </div>
        )}
        {ride && (
          <div className="card">
            <p>
              <span className="status">{ride.status}</span> · {ride.rider?.first_name ?? "Rider"} · {rupees(ride.fare)}
            </p>
            {ride.status === "DRIVER_ASSIGNED" && (
              <>
                <p>Driving to the pickup: {toPickup} m.</p>
                <button disabled={busy} onClick={() => command("arrive")}>
                  {toPickup <= ARRIVE_WITHIN_M ? "Arrived" : "Arrived (still far)"}
                </button>
                <button disabled={busy} onClick={() => command("cancel")}>
                  Cancel
                </button>
              </>
            )}
            {ride.status === "DRIVER_ARRIVED" && (
              <>
                <label>
                  Rider's PIN{" "}
                  <input value={pin} maxLength={4} inputMode="numeric" onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} />
                </label>
                <button disabled={busy || pin.length !== 4} onClick={() => command("start", { pin })}>
                  Start the trip
                </button>
                <button disabled={busy} onClick={() => command("no-show")}>
                  Rider didn't come
                </button>
              </>
            )}
            {ride.status === "IN_TRIP" && (
              <>
                <p>Driving to the drop-off: {toDropoff} m.</p>
                <button disabled={busy} onClick={() => command("complete")}>
                  Complete
                </button>
              </>
            )}
            {!active && (
              <>
                <p>{ride.status === "COMPLETED" ? "Trip completed." : `Ride ended: ${ride.status.toLowerCase().replaceAll("_", " ")}.`}</p>
                <button onClick={() => setRide(undefined)}>Done</button>
              </>
            )}
          </div>
        )}
      </aside>
      <MapView markers={markers} onClick={onMapClick} center={at} />
    </div>
  );
}
