import { describe, expect, it } from "vitest";
import { distanceM, drive, headingDeg, towards } from "./geo";

const start = { lat: 12.97, lon: 77.6 };
const north1km = { lat: 12.97 + 1000 / 6_371_008.8 / (Math.PI / 180), lon: 77.6 };

describe("geo", () => {
  it("measures distance on the server's sphere", () => {
    expect(distanceM(start, north1km)).toBeCloseTo(1000, 2);
    expect(distanceM(start, start)).toBe(0);
  });

  it("gives headings clockwise from north", () => {
    expect(headingDeg(start, north1km)).toBeCloseTo(0, 5);
    expect(headingDeg(start, { lat: 12.97, lon: 77.61 })).toBeCloseTo(90, 1);
    expect(headingDeg(north1km, start)).toBeCloseTo(180, 5);
  });

  it("moves towards a point and stops on it", () => {
    expect(distanceM(start, towards(start, north1km, 250))).toBeCloseTo(250, 1);
    expect(towards(start, north1km, 5000)).toEqual(north1km);
  });

  it("drives one tick at a steady speed, and parks without a target", () => {
    const step = drive(start, north1km, 4, 10);

    expect(distanceM(start, step.at)).toBeCloseTo(40, 1);
    expect(step.heading_deg).toBeCloseTo(0, 5);
    expect(step.speed_mps).toBe(10);
    expect(drive(start, undefined, 4, 10)).toEqual({ at: start, speed_mps: 0 });
    expect(drive(north1km, north1km, 4, 10).speed_mps).toBe(0);
  });
});
