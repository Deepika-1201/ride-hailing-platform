import type { FeatureCollection, LineString, Point } from "geojson";
import type { Availability, Category, GeoPoint, Ride, SnapshotDriver } from "../api/types";

export const STATUS_COLOURS: Record<Availability, string> = {
  AVAILABLE: "#16a34a",
  OFFERED: "#f59e0b",
  ASSIGNED: "#2563eb",
  ON_TRIP: "#7c3aed",
  OFFLINE: "#9ca3af",
};

export const STATUSES = Object.keys(STATUS_COLOURS) as Availability[];

/** MapLibre's colour expression for a driver's status. */
export const statusColour = [
  "match",
  ["get", "status"],
  ...STATUSES.flatMap((status) => [status, STATUS_COLOURS[status]]),
  "#000000",
];

export interface Bbox {
  min_lat: number;
  min_lon: number;
  max_lat: number;
  max_lon: number;
}

/** The operations map's drivers, filtered by category; a driver silent for 30 s is drawn faded. */
export function driverFeatures(drivers: SnapshotDriver[], categories: Set<Category>): FeatureCollection<Point> {
  return {
    type: "FeatureCollection",
    features: drivers
      .filter((d) => !d.category || categories.has(d.category))
      .map((d) => ({
        type: "Feature",
        id: d.driver_id,
        geometry: { type: "Point", coordinates: [d.lon, d.lat] },
        properties: { status: d.status, category: d.category ?? "", ride_id: d.ride_id ?? "", stale: d.stale },
      })),
  };
}

export function countByStatus(drivers: SnapshotDriver[], categories: Set<Category>): Record<Availability, number> {
  const counts = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Record<Availability, number>;
  for (const d of drivers) {
    if (!d.category || categories.has(d.category)) counts[d.status]++;
  }
  return counts;
}

/** Active rides as a line from pickup to drop-off; the selected one is highlighted. */
export function rideFeatures(rides: Ride[], selected?: string): FeatureCollection<LineString> {
  return {
    type: "FeatureCollection",
    features: rides.map((r) => ({
      type: "Feature",
      id: r.id,
      geometry: { type: "LineString", coordinates: [lonLat(r.pickup), lonLat(r.dropoff)] },
      properties: { ride_id: r.id, status: r.status, selected: r.id === selected },
    })),
  };
}

export interface Marker {
  at: GeoPoint;
  kind: "pickup" | "dropoff" | "driver" | "me";
}

export function markerFeatures(markers: Marker[]): FeatureCollection<Point> {
  return {
    type: "FeatureCollection",
    features: markers.map((m) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: lonLat(m.at) },
      properties: { kind: m.kind },
    })),
  };
}

/** A viewport the server accepts: minimums first and within range (ops_viewport). */
export function bboxOf(west: number, south: number, east: number, north: number): Bbox {
  const clamp = (v: number, limit: number) => Math.max(-limit, Math.min(limit, v));
  return {
    min_lat: clamp(Math.min(south, north), 90),
    min_lon: clamp(Math.min(west, east), 180),
    max_lat: clamp(Math.max(south, north), 90),
    max_lon: clamp(Math.max(west, east), 180),
  };
}

function lonLat(p: GeoPoint): [number, number] {
  return [p.lon, p.lat];
}
