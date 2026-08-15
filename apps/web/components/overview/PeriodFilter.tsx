"use client";

import { useRouter } from "next/navigation";

import { PERIOD_PRESETS, type PeriodPreset } from "@/lib/period";

import styles from "./Overview.module.css";

interface PeriodFilterProps {
  value: PeriodPreset;
}

export function PeriodFilter({ value }: PeriodFilterProps) {
  const router = useRouter();

  return (
    <div className={styles.periodFilter}>
      <label className={styles.periodFilterLabel} htmlFor="overview-period">
        Period
      </label>
      <select
        id="overview-period"
        className={styles.periodFilterSelect}
        value={value}
        onChange={(event) => {
          const params = new URLSearchParams(window.location.search);
          params.set("preset", event.target.value);
          router.push(`?${params.toString()}`);
        }}
      >
        {PERIOD_PRESETS.map((preset) => (
          <option key={preset.value} value={preset.value}>
            {preset.label}
          </option>
        ))}
      </select>
    </div>
  );
}
