// The resources and messages the app uses (docs/openapi.yaml, docs/schemas/websocket). JSON is snake_case; clients
// ignore unknown fields.

export interface GeoPoint {
  lat: number;
  lon: number;
}

export interface Money {
  amount_paise: number;
  currency: string;
}

export type Category = "AUTO" | "MINI" | "SEDAN" | "XL";
export const CATEGORIES: Category[] = ["AUTO", "MINI", "SEDAN", "XL"];

export type RideStatus =
  | "SEARCHING"
  | "DRIVER_ASSIGNED"
  | "DRIVER_ARRIVED"
  | "IN_TRIP"
  | "COMPLETED"
  | "CANCELLED_BY_RIDER"
  | "CANCELLED_BY_DRIVER"
  | "CANCELLED_BY_SYSTEM"
  | "DRIVER_NOT_FOUND";

export const ACTIVE_STATUSES: RideStatus[] = ["SEARCHING", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_TRIP"];

export type Availability = "OFFLINE" | "AVAILABLE" | "OFFERED" | "ASSIGNED" | "ON_TRIP";

export interface TokenResponse {
  access_token: string;
  expires_in: number;
  refresh_token: string;
  user: { id: string; roles: string[] };
}

export interface Quote {
  id: string;
  category: Category;
  distance_m: number;
  duration_s: number;
  fare: { total: Money };
  surge_multiplier: number;
  pickup_eta_s?: number;
  expires_at: string;
}

export interface Person {
  id: string;
  first_name: string;
}

export interface Vehicle {
  id: string;
  category: Category;
  make: string;
  model: string;
  colour: string;
  plate: string;
}

export interface Ride {
  id: string;
  status: RideStatus;
  version: number;
  city_id: string;
  category: Category;
  pickup: GeoPoint;
  dropoff: GeoPoint;
  fare: Money;
  pin?: string;
  promised_pickup_eta_s?: number;
  driver?: Person;
  vehicle?: Vehicle;
  rider?: Person;
  cancellation?: { cancelled_by: string; reason?: string };
  requested_at: string;
  assigned_at?: string;
  arrived_at?: string;
  started_at?: string;
  completed_at?: string;
}

export interface Offer {
  id: string;
  ride_id: string;
  category: Category;
  pickup: GeoPoint;
  dropoff: GeoPoint;
  pickup_distance_m: number;
  fare: Money;
  expires_in_ms?: number;
}

export interface DriverStatus {
  driver_id: string;
  status: Availability;
  version: number;
  vehicle_id?: string;
  category?: Category;
  offer_id?: string;
  ride_id?: string;
}

export interface Driver {
  id: string;
  first_name: string;
  vehicles: Vehicle[];
  status: DriverStatus;
}

export interface TimelineEntry {
  at: string;
  kind: string;
  summary: string;
}

export interface SnapshotDriver {
  driver_id: string;
  lat: number;
  lon: number;
  status: Availability;
  category?: Category;
  ride_id?: string;
  stale: boolean;
}

export type ServerMessage =
  | ({ type: "offer"; offer_id: string } & Omit<Offer, "id">)
  | { type: "offer_withdrawn"; offer_id: string; reason: string }
  | { type: "driver_status"; status: Availability; version: number; reason?: string }
  | { type: "ride_status"; ride_id: string; status: RideStatus; version: number }
  | { type: "driver_position"; ride_id: string; lat: number; lon: number; heading_deg?: number; seq: number; eta_s?: number }
  | { type: "ops_snapshot"; city_id: string; at: string; truncated?: boolean; drivers: SnapshotDriver[] }
  | { type: "reconnect"; after_ms: number }
  | { type: "error"; code: string; message: string };

export function isEnded(status: RideStatus): boolean {
  return !ACTIVE_STATUSES.includes(status);
}

export function rupees(money: Money): string {
  return `₹${(money.amount_paise / 100).toFixed(2)}`;
}
