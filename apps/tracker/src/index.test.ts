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
  it("keeps an unemitted touchpoint pending across a reload", () => {
    const h = harness();
    const first = h.make();
    const reload = h.make();

    expect(reload.visitorId).toBe(first.visitorId);
    expect(reload.sessionId).toBe(first.sessionId);
    expect(reload.page().payload.touchpoint).toEqual(first.firstTouch);
  });

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

  it("does not re-emit a touchpoint after reloading", () => {
    const h = harness();
    h.make().page();

    const reload = h.make("https://app.example/next");
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

  it("emits exactly one touchpoint per session", () => {
    const h = harness();
    const tracker = h.make();
    const events = [
      tracker.page(),
      tracker.page("https://app.example/two"),
      tracker.track("clicked"),
    ];
    expect(events.filter((event) => event.payload.touchpoint)).toHaveLength(1);

    h.advance(30 * 60 * 1000);
    events.push(tracker.page("https://app.example/return"));
    events.push(tracker.track("returned"));
    expect(events.filter((event) => event.payload.touchpoint)).toHaveLength(2);
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

  it("queues deterministic identify events without requiring an email", () => {
    const tracker = harness().make();

    const identify = tracker.identify(" application-user-42 ");

    expect(identify).toMatchObject({
      eventType: "identify",
      visitorId: tracker.visitorId,
      sessionId: tracker.sessionId,
      payload: { externalUserId: "application-user-42" },
    });
    expect(tracker.drain()).toEqual([identify]);
  });

  it("rejects blank and oversized external user IDs", () => {
    const tracker = harness().make();
    expect(() => tracker.identify("   ")).toThrow("between 1 and 160");
    expect(() => tracker.identify("x".repeat(161))).toThrow(
      "between 1 and 160",
    );
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

  it("uses the explicit URL and referrer when page rolls over a session", () => {
    const h = harness();
    const tracker = h.make();
    tracker.page();
    h.advance(30 * 60 * 1000);

    const event = tracker.page(
      "https://app.example/return?utm_medium=email",
      "https://newsletter.example/campaign#recipient",
    );
    expect(event.payload.touchpoint).toMatchObject({
      landingUrl: "https://app.example/return?utm_medium=email",
      referrerUrl: "https://newsletter.example/campaign",
      utmMedium: "email",
    });
  });

  it("expires at the exact timeout boundary", () => {
    const h = harness();
    const tracker = h.make();
    tracker.page();
    const originalSession = tracker.sessionId;
    h.advance(30 * 60 * 1000 - 1);
    expect(tracker.page().sessionId).toBe(originalSession);
    h.advance(30 * 60 * 1000);
    expect(tracker.page().sessionId).not.toBe(originalSession);
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
  it("defaults the session timeout to 30 minutes", () => {
    expect(defineTrackerConfig({ publicKey: "key" }).sessionTimeoutMs).toBe(
      30 * 60 * 1000,
    );
  });
  it("accepts a custom timeout override and rejects invalid overrides", () => {
    expect(
      defineTrackerConfig({ publicKey: "key", sessionTimeoutMs: 1234 })
        .sessionTimeoutMs,
    ).toBe(1234);
    expect(() =>
      defineTrackerConfig({ publicKey: "key", sessionTimeoutMs: 0 }),
    ).toThrow("positive session timeout");
    expect(() =>
      defineTrackerConfig({ publicKey: "key", sessionTimeoutMs: 1.5 }),
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
