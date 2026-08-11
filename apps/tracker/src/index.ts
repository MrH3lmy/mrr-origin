export const TRACKER_VERSION = "0.0.0-development";

export interface TrackerConfig {
  publicKey: string;
  endpoint?: string;
}

export type ValidatedTrackerConfig = Readonly<Required<TrackerConfig>>;

const DEFAULT_ENDPOINT = "https://events.mrrorigin.com";

export function defineTrackerConfig(
  config: TrackerConfig,
): ValidatedTrackerConfig {
  const publicKey = config.publicKey.trim();

  if (publicKey.length === 0) {
    throw new Error("MRROrigin tracker requires a public project key");
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
  });
}
