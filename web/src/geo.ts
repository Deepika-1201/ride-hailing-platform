import type { GeoPoint } from "./api/types";

const EARTH_RADIUS_M = 6_371_008.8;
const rad = (deg: number) => (deg * Math.PI) / 180;

export function distanceM(a: GeoPoint, b: GeoPoint): number {
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The initial bearing from a to b, in [0, 360). */
export function headingDeg(a: GeoPoint, b: GeoPoint): number {
  const y = Math.sin(rad(b.lon - a.lon)) * Math.cos(rad(b.lat));
  const x = Math.cos(rad(a.lat)) * Math.sin(rad(b.lat)) - Math.sin(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.cos(rad(b.lon - a.lon));
  const deg = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
  return deg >= 360 ? 0 : deg;
}

/** Moves up to stepM metres from a straight towards b, stopping at b. */
export function towards(a: GeoPoint, b: GeoPoint, stepM: number): GeoPoint {
  const d = distanceM(a, b);
  if (d <= stepM || d === 0) return b;
  const f = stepM / d;
  return { lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f };
}

export interface Step {
  at: GeoPoint;
  heading_deg?: number;
  speed_mps: number;
}

/** One tick of the demo driver's car: straight towards the target at a steady speed; parked without one. */
export function drive(at: GeoPoint, target: GeoPoint | undefined, seconds: number, speedMps: number): Step {
  if (!target || distanceM(at, target) < 1) return { at, speed_mps: 0 };
  return { at: towards(at, target, speedMps * seconds), heading_deg: headingDeg(at, target), speed_mps: speedMps };
}

export function riderPhone(n: number): string {
  return `+91${8_000_000_000 + n}`;
}

export function driverPhone(n: number): string {
  return `+91${7_000_000_000 + n}`;
}
