import { describe, expect, it } from "vitest";

import { defineTrackerConfig } from "./index";

describe("defineTrackerConfig", () => {
  it("normalizes a valid project configuration", () => {
    expect(
      defineTrackerConfig({
        publicKey: "  project_public_key  ",
        endpoint: "https://events.example.com/",
      }),
    ).toEqual({
      publicKey: "project_public_key",
      endpoint: "https://events.example.com",
    });
  });

  it("rejects a blank project key", () => {
    expect(() => defineTrackerConfig({ publicKey: " " })).toThrow(
      "requires a public project key",
    );
  });

  it("rejects insecure remote endpoints", () => {
    expect(() =>
      defineTrackerConfig({
        publicKey: "project_public_key",
        endpoint: "http://events.example.com",
      }),
    ).toThrow("must use HTTPS");
  });
});
