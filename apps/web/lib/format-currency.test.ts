import { describe, expect, it } from "vitest";

import { formatCurrency } from "./format-currency";

describe("formatCurrency", () => {
  it("formats founder-facing whole-dollar metrics", () => {
    expect(formatCurrency(3220)).toBe("$3,220");
  });

  it("supports an explicit reporting currency", () => {
    expect(formatCurrency(700, "EUR")).toBe("€700");
  });
});
