import { describe, expect, it } from "vitest";

import { isPeriodPreset, resolvePeriod } from "./period";

describe("resolvePeriod", () => {
  const now = new Date("2026-04-15T12:00:00.000Z");

  it("resolves last 7 days", () => {
    expect(resolvePeriod("7d", now)).toEqual({
      from: "2026-04-08T12:00:00.000Z",
      to: "2026-04-15T12:00:00.000Z",
    });
  });

  it("resolves last 30 days", () => {
    expect(resolvePeriod("30d", now)).toEqual({
      from: "2026-03-16T12:00:00.000Z",
      to: "2026-04-15T12:00:00.000Z",
    });
  });

  it("resolves last 90 days", () => {
    expect(resolvePeriod("90d", now).to).toBe("2026-04-15T12:00:00.000Z");
  });

  it("resolves month to date to the first of the current UTC month", () => {
    expect(resolvePeriod("mtd", now)).toEqual({
      from: "2026-04-01T00:00:00.000Z",
      to: "2026-04-15T12:00:00.000Z",
    });
  });
});

describe("isPeriodPreset", () => {
  it("accepts known presets", () => {
    expect(isPeriodPreset("30d")).toBe(true);
  });

  it("rejects unknown or missing values", () => {
    expect(isPeriodPreset("60d")).toBe(false);
    expect(isPeriodPreset(undefined)).toBe(false);
  });
});
