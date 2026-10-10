import { describe, expect, it } from "vitest";
import type { Category, Ride, SnapshotDriver } from "../api/types";
import { bboxOf, countByStatus, driverFeatures, rideFeatures } from "./layers";

const drivers: SnapshotDriver[] = [
  { driver_id: "d1", lat: 12.97, lon: 77.6, status: "AVAILABLE", category: "MINI", stale: false },
  { driver_id: "d2", lat: 12.98, lon: 77.61, status: "ON_TRIP", category: "SEDAN", ride_id: "r2", stale: false },
  { driver_id: "d3", lat: 12.99, lon: 77.62, status: "AVAILABLE", category: "AUTO", stale: true },
  { driver_id: "d4", lat: 13.0, lon: 77.63, status: "OFFERED", stale: false },
];

describe("map layers", () => {
  it("draws only the chosen categories, and drivers whose category the snapshot leaves out", () => {
    const shown = driverFeatures(drivers, new Set<Category>(["MINI", "AUTO"]));

    expect(shown.features.map((f) => f.id)).toEqual(["d1", "d3", "d4"]);
    expect(shown.features[0].geometry.coordinates).toEqual([77.6, 12.97]);
    expect(shown.features[1].properties).toEqual({ status: "AVAILABLE", category: "AUTO", ride_id: "", stale: true });
  });

  it("counts drivers by status within the chosen categories", () => {
    const counts = countByStatus(drivers, new Set<Category>(["MINI", "SEDAN"]));

    expect(counts).toEqual({ AVAILABLE: 1, OFFERED: 1, ASSIGNED: 0, ON_TRIP: 1, OFFLINE: 0 });
  });

  it("draws rides from pickup to drop-off and marks the selected one", () => {
    const ride = (id: string) =>
      ({ id, status: "IN_TRIP", pickup: { lat: 12.97, lon: 77.6 }, dropoff: { lat: 12.93, lon: 77.62 } }) as Ride;

    const lines = rideFeatures([ride("r1"), ride("r2")], "r2");

    expect(lines.features[0].geometry.coordinates).toEqual([
      [77.6, 12.97],
      [77.62, 12.93],
    ]);
    expect(lines.features.map((f) => f.properties?.selected)).toEqual([false, true]);
  });

  it("makes a viewport the server accepts: minimums first, within range", () => {
    expect(bboxOf(77.7, 13.0, 77.5, 12.9)).toEqual({ min_lat: 12.9, min_lon: 77.5, max_lat: 13.0, max_lon: 77.7 });
    expect(bboxOf(-200, -95, 200, 95)).toEqual({ min_lat: -90, min_lon: -180, max_lat: 90, max_lon: 180 });
  });
});
