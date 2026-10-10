import { useMemo, useRef, useState } from "react";
import { Client } from "../api/client";
import { CATEGORIES, type Category, type GeoPoint, type Quote, type Ride, isEnded, rupees } from "../api/types";
import { useRealtime } from "../api/useRealtime";
import { riderPhone } from "../geo";
import { type Marker, markerFeatures } from "../map/layers";
import { MapView } from "../map/MapView";

/** Above every scenario's rider pool, so the person and the simulator never book as the same rider. */
const DEMO_RIDER = 9001;

interface DriverPosition {
  at: GeoPoint;
  seq: number;
  eta_s?: number;
}

/** FR-S6: a person as one rider: pick the trip on the map, quote, book, follow the driver, ride and rate. */
export function Rider() {
  const client = useMemo(() => new Client(), []);
  const [number, setNumber] = useState(DEMO_RIDER);
  const [signedIn, setSignedIn] = useState(false);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [pickup, setPickup] = useState<GeoPoint>();
  const [dropoff, setDropoff] = useState<GeoPoint>();
  const [category, setCategory] = useState<Category>("MINI");
  const [quote, setQuote] = useState<Quote>();
  const [ride, setRide] = useState<Ride>();
  const [driver, setDriver] = useState<DriverPosition>();
  const [rated, setRated] = useState(false);
  const bookingKey = useRef<string | undefined>(undefined);

  const run = async (what: string, action: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    try {
      await action();
    } catch (e) {
      setError(`${what}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const keep = (next: Ride) => setRide((current) => (!current || current.id !== next.id || next.version >= current.version ? next : current));

  const refresh = (rideId: string) =>
    client
      .ride(rideId)
      .then(keep)
      .catch(() => undefined);

  const { state: socket } = useRealtime(
    client,
    signedIn,
    (m) => {
      if (m.type === "ride_status" && ride?.id === m.ride_id && m.version > ride.version) {
        void refresh(m.ride_id);
      } else if (m.type === "driver_position" && ride?.id === m.ride_id && (!driver || m.seq > driver.seq)) {
        setDriver({ at: { lat: m.lat, lon: m.lon }, seq: m.seq, eta_s: m.eta_s });
      }
    },
    // Pushes are best effort: after every connect, read the active ride again; one that ended meanwhile is read by ID.
    () => {
      void client.riderActiveRide().then((active) => {
        if (active) keep(active);
        else if (ride && !isEnded(ride.status)) void refresh(ride.id);
      });
    },
  );

  const signIn = () =>
    run("Signing in", async () => {
      await client.signIn(riderPhone(number));
      // A rider who exists only through sign-in gets their profile, and cash, on the first read.
      await client.call("GET", "/v1/riders/me");
      setSignedIn(true);
    });

  const onMapClick = (at: GeoPoint) => {
    if (ride && !isEnded(ride.status)) return;
    setQuote(undefined);
    if (!pickup || dropoff) {
      setPickup(at);
      setDropoff(undefined);
    } else {
      setDropoff(at);
    }
  };

  const getQuote = () =>
    run("Quote", async () => {
      setQuote(await client.quote(pickup!, dropoff!, category));
      bookingKey.current = Client.newKey();
    });

  const book = () =>
    run("Booking", async () => {
      const booked = await client.book(quote!.id, bookingKey.current!);
      setRide(booked);
      setDriver(undefined);
      setRated(false);
    });

  const cancel = () => run("Cancelling", async () => keep(await client.rideCommand(ride!.id, "cancel", Client.newKey())));

  const rate = (stars: number) =>
    run("Rating", async () => {
      await client.rate(ride!.id, stars, Client.newKey());
      setRated(true);
    });

  const newRide = () => {
    setRide(undefined);
    setQuote(undefined);
    setDriver(undefined);
    setPickup(undefined);
    setDropoff(undefined);
  };

  const markers = useMemo(() => {
    const list: Marker[] = [];
    const from = ride?.pickup ?? pickup;
    const to = ride?.dropoff ?? dropoff;
    if (from) list.push({ at: from, kind: "pickup" });
    if (to) list.push({ at: to, kind: "dropoff" });
    if (driver && ride && !isEnded(ride.status)) list.push({ at: driver.at, kind: "driver" });
    return markerFeatures(list);
  }, [ride, pickup, dropoff, driver]);

  if (!signedIn) {
    return (
      <div className="screen">
        <aside className="panel">
          <h2>Rider</h2>
          {error && <p className="error">{error}</p>}
          <label>
            Rider number <input type="number" min={1} value={number} onChange={(e) => setNumber(Number(e.target.value))} />
          </label>
          <p className="muted">{riderPhone(number)}</p>
          <button disabled={busy} onClick={signIn}>
            Sign in
          </button>
        </aside>
        <MapView />
      </div>
    );
  }

  const active = ride && !isEnded(ride.status);
  return (
    <div className="screen">
      <aside className="panel">
        <h2>Rider {number}</h2>
        <p className="muted">Live: {socket}</p>
        {error && <p className="error">{error}</p>}
        {!ride && (
          <>
            <p>{!pickup ? "Pickup not set" : !dropoff ? "Drop-off not set" : "Trip ready"}</p>
            <label>
              Category{" "}
              <select value={category} onChange={(e) => { setCategory(e.target.value as Category); setQuote(undefined); }}>
                {CATEGORIES.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </label>
            <button disabled={busy || !pickup || !dropoff} onClick={getQuote}>
              Get a quote
            </button>
            {quote && (
              <div className="card">
                <p>
                  <b>{rupees(quote.fare.total)}</b> · {(quote.distance_m / 1000).toFixed(1)} km · {Math.round(quote.duration_s / 60)} min
                  {quote.surge_multiplier > 1 && <> · surge ×{quote.surge_multiplier}</>}
                </p>
                <p className="muted">
                  {quote.pickup_eta_s === undefined ? "No driver nearby yet" : `Pickup in about ${Math.ceil(quote.pickup_eta_s / 60)} min`} ·
                  valid until {new Date(quote.expires_at).toLocaleTimeString()}
                </p>
                <button disabled={busy} onClick={book}>
                  Book
                </button>
              </div>
            )}
          </>
        )}
        {ride && (
          <div className="card">
            <p>
              <span className="status">{ride.status}</span> · {ride.category} · {rupees(ride.fare)}
            </p>
            <p>{describe(ride, driver)}</p>
            {ride.pin && (ride.status === "DRIVER_ASSIGNED" || ride.status === "DRIVER_ARRIVED") && (
              <p>
                PIN for your driver: <b className="pin">{ride.pin}</b>
              </p>
            )}
            {active && ride.status !== "IN_TRIP" && (
              <button disabled={busy} onClick={cancel}>
                Cancel the ride
              </button>
            )}
            {ride.status === "COMPLETED" && !rated && (
              <p>
                Rate your driver:{" "}
                {[1, 2, 3, 4, 5].map((stars) => (
                  <button key={stars} disabled={busy} onClick={() => rate(stars)}>
                    {stars}★
                  </button>
                ))}
              </p>
            )}
            {!active && (
              <button disabled={busy} onClick={newRide}>
                New ride
              </button>
            )}
          </div>
        )}
      </aside>
      <MapView markers={markers} onClick={onMapClick} />
    </div>
  );
}

function describe(ride: Ride, driver?: DriverPosition): string {
  const who = ride.driver?.first_name ?? "Your driver";
  const car = ride.vehicle ? ` in a ${ride.vehicle.colour} ${ride.vehicle.make} ${ride.vehicle.model} (${ride.vehicle.plate})` : "";
  const minutes = driver?.eta_s === undefined ? undefined : Math.max(1, Math.round(driver.eta_s / 60));
  switch (ride.status) {
    case "SEARCHING":
      return "Finding you a driver…";
    case "DRIVER_ASSIGNED":
      return `${who} is coming${car}${minutes ? `, about ${minutes} min away` : ""}.`;
    case "DRIVER_ARRIVED":
      return `${who} is at the pickup${car}.`;
    case "IN_TRIP":
      return `On the way${minutes ? `, arriving in about ${minutes} min` : ""}.`;
    case "COMPLETED":
      return "You've arrived.";
    case "DRIVER_NOT_FOUND":
      return "No driver was found; try again in a minute.";
    default:
      return `Cancelled by ${ride.cancellation?.cancelled_by.toLowerCase() ?? "someone"}${ride.cancellation?.reason ? `: ${ride.cancellation.reason}` : ""}.`;
  }
}
