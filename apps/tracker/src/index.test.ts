import { describe, expect, it } from "vitest";

import { createTracker, defineTrackerConfig, parseAcquisition } from "./index";

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length() {
    return this.values.size;
  }
  clear() {
    this.values.clear();
  }
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  key(index: number) {
    return [...this.values.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

function harness(storage: Storage = new MemoryStorage()) {
  let time = Date.parse("2026-08-11T10:00:00.000Z");
  let sequence = 0;
  const make = (href = "https://app.example/landing?utm_source=Search") =>
    createTracker({
      publicKey: "project_public_key",
      sessionTimeoutMs: 30 * 60 * 1000,
      storage,
      now: () => time,
      generateId: () => `id-${++sequence}`,
      location: { href },
      document: { referrer: "https://search.example/results#private" },
    });
  return { make, advance: (ms: number) => (time += ms) };
}

describe("browser session capture", () => {
  it("preserves the visitor and active session across reloads", () => {
    const h = harness();
    const first = h.make();
    first.page();
    h.advance(10_000);
    const reload = h.make("https://app.example/next");

    expect(reload.visitorId).toBe(first.visitorId);
    expect(reload.sessionId).toBe(first.sessionId);
    expect(reload.firstTouch).toEqual(first.firstTouch);
    expect(reload.page().payload.touchpoint).toBeUndefined();
  });

  it("rolls over an expired session while preserving first touch", () => {
    const h = harness();
    const first = h.make();
    first.page();
    h.advance(30 * 60 * 1000);
    const later = h.make("https://app.example/pricing?utm_source=Newsletter");
    const event = later.page();

    expect(later.visitorId).toBe(first.visitorId);
    expect(later.sessionId).not.toBe(first.sessionId);
    expect(later.firstTouch).toEqual(first.firstTouch);
    expect(event.payload.touchpoint).toMatchObject({
      landingUrl: "https://app.example/pricing?utm_source=Newsletter",
      utmSource: "Newsletter",
      sessionId: later.sessionId,
    });
  });

  it("records only the first event's touchpoint in each later session", () => {
    const h = harness();
    const first = h.make();
    first.page();
    h.advance(30 * 60 * 1000);
    const later = h.make("https://app.example/offer?utm_campaign=fall");

    expect(later.track("signup_started").payload.touchpoint?.utmCampaign).toBe(
      "fall",
    );
    expect(
      later.page("https://app.example/signup").payload.touchpoint,
    ).toBeUndefined();
    expect(later.firstTouch.utmSource).toBe("Search");
  });

  it("queues pageviews and custom events with unique deterministic IDs", () => {
    const tracker = harness().make();
    const page = tracker.page();
    const custom = tracker.track(" trial_started ", {
      plan: "starter",
      seats: 2,
    });

    expect(page.externalEventId).not.toBe(custom.externalEventId);
    expect(custom).toMatchObject({
      eventType: "custom",
      payload: { name: "trial_started" },
    });
    expect(tracker.drain()).toEqual([page, custom]);
    expect(tracker.drain()).toEqual([]);
  });

  it("does not derive identity from browser attributes", () => {
    const one = createTracker({
      publicKey: "key",
      sessionTimeoutMs: 1,
      storage: new MemoryStorage(),
      location: { href: "https://example.com" },
      document: { referrer: "" },
    });
    const two = createTracker({
      publicKey: "key",
      sessionTimeoutMs: 1,
      storage: new MemoryStorage(),
      location: { href: "https://example.com" },
      document: { referrer: "" },
    });
    expect(one.visitorId).not.toBe(two.visitorId);
  });

  it("rolls over a long-lived tracker after inactivity", () => {
    const h = harness();
    const tracker = h.make();
    const previousSession = tracker.sessionId;
    tracker.page();
    h.advance(30 * 60 * 1000);
    const event = tracker.page("https://app.example/return");
    expect(event.sessionId).not.toBe(previousSession);
    expect(event.payload.touchpoint?.landingUrl).toBe(
      "https://app.example/return",
    );
  });
});

describe("acquisition parsing", () => {
  it("normalizes URLs and all supported UTM parameters", () => {
    expect(
      parseAcquisition(
        "https://user:secret@EXAMPLE.com:443/a?utm_source=%20Google%20&utm_medium=cpc&utm_campaign=spring&utm_term=shoes&utm_content=hero#token",
        "https://REFERRER.example/path#fragment",
      ),
    ).toEqual({
      landingUrl:
        "https://example.com/a?utm_source=%20Google%20&utm_medium=cpc&utm_campaign=spring&utm_term=shoes&utm_content=hero",
      referrerUrl: "https://referrer.example/path",
      utmSource: "Google",
      utmMedium: "cpc",
      utmCampaign: "spring",
      utmTerm: "shoes",
      utmContent: "hero",
    });
  });

  it("drops malformed and non-HTTP referrers", () => {
    expect(parseAcquisition("https://example.com/", "not a url")).toEqual({
      landingUrl: "https://example.com/",
    });
    expect(
      parseAcquisition("https://example.com/", "javascript:alert(1)"),
    ).toEqual({ landingUrl: "https://example.com/" });
  });
});

describe("storage failures", () => {
  it("recovers from malformed storage", () => {
    const storage = new MemoryStorage();
    storage.setItem("mrrorigin:v1:project_public_key", "{broken");
    expect(() => harness(storage).make().page()).not.toThrow();
  });

  it("continues when storage access throws", () => {
    const storage = new Proxy(new MemoryStorage(), {
      get() {
        throw new Error("denied");
      },
    });
    const tracker = harness(storage).make();
    expect(tracker.page().visitorId).toBe(tracker.visitorId);
  });
});

describe("defineTrackerConfig", () => {
  it("normalizes a valid project configuration", () => {
    expect(
      defineTrackerConfig({
        publicKey: " key ",
        endpoint: "https://events.example.com/",
        sessionTimeoutMs: 60_000,
      }),
    ).toEqual({
      publicKey: "key",
      endpoint: "https://events.example.com",
      sessionTimeoutMs: 60_000,
    });
  });
  it("requires callers to select the currently undefined timeout policy", () => {
    expect(() =>
      defineTrackerConfig({ publicKey: "key", sessionTimeoutMs: 0 }),
    ).toThrow("positive session timeout");
  });
  it("rejects blank keys and insecure endpoints", () => {
    expect(() =>
      defineTrackerConfig({ publicKey: " ", sessionTimeoutMs: 1 }),
    ).toThrow("public project key");
    expect(() =>
      defineTrackerConfig({
        publicKey: "key",
        endpoint: "http://events.example.com",
        sessionTimeoutMs: 1,
      }),
    ).toThrow("must use HTTPS");
  });
});
