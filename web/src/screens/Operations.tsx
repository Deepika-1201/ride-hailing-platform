import { useEffect, useMemo, useRef, useState } from "react";
import { Client } from "../api/client";
import { Realtime, type SocketState } from "../api/realtime";
import {
  ACTIVE_STATUSES,
  CATEGORIES,
  type Category,
  type Ride,
  type SnapshotDriver,
  type TimelineEntry,
} from "../api/types";
import { type Bbox, countByStatus, driverFeatures, rideFeatures, STATUS_COLOURS, STATUSES } from "../map/layers";
import { MapView } from "../map/MapView";

const OPS_PHONE = "+919000000001";
const CITY = "blr";

/** FR-O5: the live map of drivers by status and of active rides; a ride's timeline on click (FR-O1). */
export function Operations() {
  const client = useMemo(() => new Client(), []);
  const [signedIn, setSignedIn] = useState(false);
  const [error, setError] = useState<string>();
  const [socket, setSocket] = useState<SocketState>("closed");
  const [drivers, setDrivers] = useState<SnapshotDriver[]>([]);
  const [snapshotAt, setSnapshotAt] = useState<string>();
  const [truncated, setTruncated] = useState(false);
  const [categories, setCategories] = useState(() => new Set<Category>(CATEGORIES));
  const [rides, setRides] = useState<Ride[]>([]);
  const [selected, setSelected] = useState<string>();
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const realtime = useRef<Realtime | undefined>(undefined);
  const viewport = useRef<Bbox | undefined>(undefined);

  useEffect(() => {
    let stopped = false;
    client
      .signIn(OPS_PHONE)
      .then(() => {
        if (stopped) return;
        setSignedIn(true);
        const rt = new Realtime(client, {
          onMessage: (m) => {
            if (m.type === "ops_snapshot") {
              setDrivers(m.drivers);
              setSnapshotAt(m.at);
              setTruncated(Boolean(m.truncated));
            }
          },
          // The viewport is per connection: send it again after every reconnect.
          onConnect: () => viewport.current && rt.send({ type: "ops_viewport", city_id: CITY, bbox: viewport.current }),
          onState: setSocket,
        });
        realtime.current = rt;
        rt.start();
      })
      .catch((e) => setError(`Signing in as operations: ${e}`));
    return () => {
      stopped = true;
      realtime.current?.stop();
    };
  }, [client]);

  useEffect(() => {
    if (!signedIn) return;
    const load = () =>
      client
        .opsRides(ACTIVE_STATUSES, CITY)
        .then(setRides)
        .catch((e) => setError(`Active rides: ${e}`));
    void load();
    const timer = setInterval(load, 10_000);
    return () => clearInterval(timer);
  }, [client, signedIn]);

  useEffect(() => {
    if (!selected) return;
    client
      .timeline(selected)
      .then(setTimeline)
      .catch((e) => setError(`Timeline: ${e}`));
  }, [client, selected]);

  const onBounds = (bbox: Bbox) => {
    viewport.current = bbox;
    realtime.current?.send({ type: "ops_viewport", city_id: CITY, bbox });
  };
  const toggle = (c: Category) =>
    setCategories((current) => {
      const next = new Set(current);
      if (!next.delete(c)) next.add(c);
      return next;
    });
  const counts = countByStatus(drivers, categories);
  const shown = useMemo(() => rides.filter((r) => categories.has(r.category)), [rides, categories]);
  const driverData = useMemo(() => driverFeatures(drivers, categories), [drivers, categories]);
  const rideData = useMemo(() => rideFeatures(shown, selected), [shown, selected]);

  return (
    <div className="screen">
      <aside className="panel">
        <h2>Operations</h2>
        {error && <p className="error">{error}</p>}
        <p className="muted">
          Live: {socket}
          {snapshotAt && <> · {new Date(snapshotAt).toLocaleTimeString()}</>}
          {truncated && <> · showing the first 5,000</>}
        </p>
        <ul className="legend">
          {STATUSES.map((s) => (
            <li key={s}>
              <span className="dot" style={{ background: STATUS_COLOURS[s] }} /> {s.toLowerCase().replace("_", " ")}{" "}
              <b>{counts[s]}</b>
            </li>
          ))}
        </ul>
        <div className="filters">
          {CATEGORIES.map((c) => (
            <label key={c}>
              <input type="checkbox" checked={categories.has(c)} onChange={() => toggle(c)} /> {c}
            </label>
          ))}
        </div>
        <h3>Active rides ({shown.length})</h3>
        <ul className="rides">
          {shown.map((r) => (
            <li key={r.id} className={r.id === selected ? "selected" : ""} onClick={() => setSelected(r.id)}>
              <code>{r.id.slice(-8)}</code> {r.category} <span className="status">{r.status}</span>
            </li>
          ))}
        </ul>
        {selected && (
          <>
            <h3>
              Timeline <code>{selected.slice(-8)}</code>
            </h3>
            <ol className="timeline">
              {timeline.map((e, i) => (
                <li key={i}>
                  <span className="muted">{new Date(e.at).toLocaleTimeString()}</span> <b>{e.kind}</b> {e.summary}
                </li>
              ))}
            </ol>
          </>
        )}
      </aside>
      <MapView drivers={driverData} rides={rideData} onBounds={onBounds} onRideClick={setSelected} />
    </div>
  );
}
