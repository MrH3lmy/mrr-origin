export const TRACKER_VERSION = "0.0.0-development";

export interface TrackerConfig {
  publicKey: string;
  endpoint?: string;
  /** Session inactivity timeout. Defaults to 30 minutes. */
  sessionTimeoutMs?: number;
  storage?: Storage;
  now?: () => number;
  generateId?: () => string;
  location?: Pick<Location, "href">;
  document?: Pick<Document, "referrer">;
}

export interface Acquisition {
  landingUrl: string;
  referrerUrl?: string;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmTerm?: string;
  utmContent?: string;
}

export interface Touchpoint extends Acquisition {
  occurredAt: string;
  sessionId: string;
}

export interface TrackerEvent {
  externalEventId: string;
  eventType: "page_view" | "custom" | "identify";
  occurredAt: string;
  visitorId: string;
  sessionId: string;
  payload: Readonly<{
    name?: string;
    pageUrl: string;
    properties?: Readonly<Record<string, string | number | boolean | null>>;
    touchpoint?: Touchpoint;
    externalUserId?: string;
  }>;
}

export interface Tracker {
  page(url?: string, referrer?: string): TrackerEvent;
  track(
    name: string,
    properties?: Readonly<Record<string, string | number | boolean | null>>,
  ): TrackerEvent;
  identify(externalUserId: string): TrackerEvent;
  drain(): TrackerEvent[];
  readonly visitorId: string;
  readonly sessionId: string;
  readonly firstTouch: Readonly<Touchpoint>;
}

export type ValidatedTrackerConfig = Readonly<
  Required<Pick<TrackerConfig, "publicKey" | "endpoint" | "sessionTimeoutMs">>
>;

const DEFAULT_ENDPOINT = "https://events.mrrorigin.com";
export const DEFAULT_SESSION_TIMEOUT_MS = 30 * 60 * 1000;
const STORAGE_PREFIX = "mrrorigin:v1:";

interface StoredState {
  version: 1;
  visitorId: string;
  sessionId: string;
  sessionStartedAt: number;
  lastActivityAt: number;
  firstTouch: Touchpoint;
  sessionTouchpoint: Touchpoint;
  touchpointEmitted: boolean;
}

export function defineTrackerConfig(
  config: TrackerConfig,
): ValidatedTrackerConfig {
  const publicKey = config.publicKey.trim();

  if (publicKey.length === 0) {
    throw new Error("MRROrigin tracker requires a public project key");
  }
  if (
    config.sessionTimeoutMs !== undefined &&
    (!Number.isSafeInteger(config.sessionTimeoutMs) ||
      config.sessionTimeoutMs <= 0)
  ) {
    throw new Error("MRROrigin tracker requires a positive session timeout");
  }

  const endpoint = new URL(config.endpoint ?? DEFAULT_ENDPOINT);
  if (endpoint.protocol !== "https:" && endpoint.hostname !== "localhost") {
    throw new Error(
      "MRROrigin tracker endpoint must use HTTPS outside localhost",
    );
  }

  return Object.freeze({
    publicKey,
    endpoint: endpoint.toString().replace(/\/$/, ""),
    sessionTimeoutMs: config.sessionTimeoutMs ?? DEFAULT_SESSION_TIMEOUT_MS,
  });
}

export function createTracker(config: TrackerConfig): Tracker {
  const validated = defineTrackerConfig(config);
  const now = config.now ?? Date.now;
  const generateId = config.generateId ?? (() => crypto.randomUUID());
  const browserLocation = config.location ?? globalThis.location;
  const browserDocument = config.document ?? globalThis.document;
  const storage = config.storage ?? safeLocalStorage();
  const storageKey = `${STORAGE_PREFIX}${validated.publicKey}`;
  const queue: TrackerEvent[] = [];
  let currentPage = normalizeUrl(browserLocation?.href ?? "", true);
  const timestamp = now();
  const restored = readState(storage, storageKey);
  const isActive =
    restored !== undefined &&
    timestamp >= restored.lastActivityAt &&
    timestamp - restored.lastActivityAt < validated.sessionTimeoutMs;

  let state: StoredState;
  let pendingTouchpoint: Touchpoint | undefined;
  if (restored && isActive) {
    state = { ...restored, lastActivityAt: timestamp };
    pendingTouchpoint = restored.touchpointEmitted
      ? undefined
      : restored.sessionTouchpoint;
  } else {
    const acquisition = parseAcquisition(
      currentPage,
      browserDocument?.referrer ?? "",
    );
    const sessionId = generateId();
    const touchpoint: Touchpoint = {
      ...acquisition,
      occurredAt: new Date(timestamp).toISOString(),
      sessionId,
    };
    state = {
      version: 1,
      visitorId: restored?.visitorId ?? generateId(),
      sessionId,
      sessionStartedAt: timestamp,
      lastActivityAt: timestamp,
      firstTouch: restored?.firstTouch ?? touchpoint,
      sessionTouchpoint: touchpoint,
      touchpointEmitted: false,
    };
    pendingTouchpoint = touchpoint;
  }
  writeState(storage, storageKey, state);

  function createEvent(
    eventType: TrackerEvent["eventType"],
    name?: string,
    properties?: Readonly<Record<string, string | number | boolean | null>>,
    rolloverReferrer = browserDocument?.referrer ?? "",
    externalUserId?: string,
  ): TrackerEvent {
    const occurredAtMs = now();
    if (
      occurredAtMs < state.lastActivityAt ||
      occurredAtMs - state.lastActivityAt >= validated.sessionTimeoutMs
    ) {
      const sessionId = generateId();
      pendingTouchpoint = {
        ...parseAcquisition(currentPage, rolloverReferrer),
        occurredAt: new Date(occurredAtMs).toISOString(),
        sessionId,
      };
      state.sessionId = sessionId;
      state.sessionStartedAt = occurredAtMs;
      state.sessionTouchpoint = pendingTouchpoint;
      state.touchpointEmitted = false;
    }
    state.lastActivityAt = occurredAtMs;
    const event: TrackerEvent = Object.freeze({
      externalEventId: generateId(),
      eventType,
      occurredAt: new Date(occurredAtMs).toISOString(),
      visitorId: state.visitorId,
      sessionId: state.sessionId,
      payload: Object.freeze({
        ...(name === undefined ? {} : { name }),
        ...(externalUserId === undefined ? {} : { externalUserId }),
        pageUrl: currentPage,
        ...(properties === undefined ? {} : { properties: { ...properties } }),
        ...(pendingTouchpoint === undefined
          ? {}
          : { touchpoint: pendingTouchpoint }),
      }),
    });
    if (pendingTouchpoint !== undefined) state.touchpointEmitted = true;
    pendingTouchpoint = undefined;
    writeState(storage, storageKey, state);
    queue.push(event);
    return event;
  }

  return {
    page(url = browserLocation?.href ?? currentPage, referrer) {
      currentPage = normalizeUrl(url, true);
      if (referrer !== undefined && pendingTouchpoint) {
        pendingTouchpoint = {
          ...parseAcquisition(currentPage, referrer),
          occurredAt: pendingTouchpoint.occurredAt,
          sessionId: pendingTouchpoint.sessionId,
        };
        if (state.firstTouch.sessionId === state.sessionId) {
          state.firstTouch = pendingTouchpoint;
        }
      }
      return createEvent(
        "page_view",
        undefined,
        undefined,
        referrer ?? browserDocument?.referrer ?? "",
      );
    },
    track(name, properties) {
      const normalizedName = name.trim();
      if (!normalizedName) throw new Error("Custom event name cannot be blank");
      return createEvent("custom", normalizedName, properties);
    },
    identify(externalUserId) {
      const normalizedId = externalUserId.trim();
      if (!normalizedId || normalizedId.length > 160) {
        throw new Error(
          "External user ID must be between 1 and 160 characters",
        );
      }
      return createEvent(
        "identify",
        undefined,
        undefined,
        browserDocument?.referrer ?? "",
        normalizedId,
      );
    },
    drain() {
      return queue.splice(0, queue.length);
    },
    get visitorId() {
      return state.visitorId;
    },
    get sessionId() {
      return state.sessionId;
    },
    get firstTouch() {
      return Object.freeze({ ...state.firstTouch });
    },
  };
}

export function parseAcquisition(
  pageUrl: string,
  referrer: string,
): Acquisition {
  const landingUrl = normalizeUrl(pageUrl, true);
  const url = new URL(landingUrl);
  const referrerUrl = referrer.trim() ? normalizeUrl(referrer, false) : "";
  const utm = (name: string): string | undefined => {
    const value = url.searchParams.get(name)?.trim();
    return value ? value.slice(0, 255) : undefined;
  };
  return {
    landingUrl,
    ...(referrerUrl ? { referrerUrl } : {}),
    ...(utm("utm_source") ? { utmSource: utm("utm_source") } : {}),
    ...(utm("utm_medium") ? { utmMedium: utm("utm_medium") } : {}),
    ...(utm("utm_campaign") ? { utmCampaign: utm("utm_campaign") } : {}),
    ...(utm("utm_term") ? { utmTerm: utm("utm_term") } : {}),
    ...(utm("utm_content") ? { utmContent: utm("utm_content") } : {}),
  };
}

function normalizeUrl(value: string, required: boolean): string {
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:")
      throw new Error();
    url.username = "";
    url.password = "";
    url.hash = "";
    return url.toString();
  } catch {
    if (required)
      throw new Error("Tracker page URL must be an absolute HTTP(S) URL");
    return "";
  }
}

function safeLocalStorage(): Storage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

function readState(
  storage: Storage | undefined,
  key: string,
): StoredState | undefined {
  try {
    const value: unknown = JSON.parse(storage?.getItem(key) ?? "null");
    if (!isStoredState(value)) return undefined;
    return value;
  } catch {
    return undefined;
  }
}

function writeState(
  storage: Storage | undefined,
  key: string,
  state: StoredState,
): void {
  try {
    storage?.setItem(key, JSON.stringify(state));
  } catch {
    // Tracking continues in memory when storage is denied or unavailable.
  }
}

function isStoredState(value: unknown): value is StoredState {
  if (!value || typeof value !== "object") return false;
  const state = value as Partial<StoredState>;
  return (
    state.version === 1 &&
    typeof state.visitorId === "string" &&
    state.visitorId.length > 0 &&
    typeof state.sessionId === "string" &&
    state.sessionId.length > 0 &&
    Number.isFinite(state.sessionStartedAt) &&
    Number.isFinite(state.lastActivityAt) &&
    !!state.firstTouch &&
    typeof state.firstTouch.landingUrl === "string" &&
    typeof state.firstTouch.occurredAt === "string" &&
    typeof state.firstTouch.sessionId === "string" &&
    !!state.sessionTouchpoint &&
    typeof state.sessionTouchpoint.landingUrl === "string" &&
    typeof state.sessionTouchpoint.occurredAt === "string" &&
    typeof state.sessionTouchpoint.sessionId === "string" &&
    typeof state.touchpointEmitted === "boolean"
  );
}
