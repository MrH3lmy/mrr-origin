export type PeriodPreset = "7d" | "30d" | "90d" | "mtd";

export const PERIOD_PRESETS: { value: PeriodPreset; label: string }[] = [
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "90d", label: "Last 90 days" },
  { value: "mtd", label: "Month to date" },
];

export const DEFAULT_PERIOD_PRESET: PeriodPreset = "30d";

export function isPeriodPreset(
  value: string | undefined,
): value is PeriodPreset {
  return PERIOD_PRESETS.some((preset) => preset.value === value);
}

/** Resolves a UI period preset to an explicit [from, to) instant range. Purely a display default. */
export function resolvePeriod(
  preset: PeriodPreset,
  now: Date = new Date(),
): { from: string; to: string } {
  const to = now;
  let from: Date;
  switch (preset) {
    case "7d":
      from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
      break;
    case "90d":
      from = new Date(to.getTime() - 90 * 24 * 60 * 60 * 1000);
      break;
    case "mtd":
      from = new Date(Date.UTC(to.getUTCFullYear(), to.getUTCMonth(), 1));
      break;
    case "30d":
    default:
      from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
      break;
  }
  return { from: from.toISOString(), to: to.toISOString() };
}
