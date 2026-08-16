"use client";

import { useEffect, useState } from "react";

import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { getRetentionCohorts } from "@/lib/api/reporting";
import type {
  ComparisonDimension,
  RetentionAgeCell,
  RetentionCohortRow,
  RetentionCohorts,
} from "@/lib/api/types";
import { formatMoneyMinor } from "@/lib/format-currency";

import overviewStyles from "../overview/Overview.module.css";
import sourcesStyles from "../sources/Sources.module.css";
import styles from "./Retention.module.css";

interface CohortHeatmapProps {
  workspaceId: string;
  projectId: string;
  dimension: ComparisonDimension;
  source: string | null;
  campaign: string | null;
  campaignMissing: boolean;
  onDrillDown: (dimensionValue: string | null) => void;
}

const PERIOD_FORMATTER = new Intl.DateTimeFormat("en-US", {
  month: "short",
  year: "numeric",
  timeZone: "UTC",
});

function formatPeriod(periodStart: string): string {
  return PERIOD_FORMATTER.format(new Date(periodStart));
}

function dimensionLabel(dimension: ComparisonDimension): string {
  switch (dimension) {
    case "SOURCE":
      return "Source";
    case "CAMPAIGN":
      return "Campaign";
    case "LANDING_PAGE":
      return "Landing page";
  }
}

/** Same "no value at this level" convention as Sources' ComparisonTable -- see `RetentionCohortRow`. */
function noEvidenceLabel(
  dimension: ComparisonDimension,
  attributed: boolean,
): string {
  switch (dimension) {
    case "SOURCE":
      return attributed ? "No source captured" : "Unattributed";
    case "CAMPAIGN":
      return "No campaign captured";
    case "LANDING_PAGE":
      return "No landing page captured";
  }
}

/**
 * Background-intensity bucket for a mature age cell. Purely decorative -- `AgeCellContent` always
 * renders the exact retained-MRR amount and percentage as text underneath, so the color is never
 * the only carrier of meaning (DESIGN_SYSTEM.md).
 */
function healthClass(retentionPercentage: number | null): string {
  if (retentionPercentage === null) return "";
  if (retentionPercentage >= 1) return styles.ageCellHealthy;
  if (retentionPercentage >= 0.7) return styles.ageCellModerate;
  if (retentionPercentage > 0) return styles.ageCellWeak;
  return styles.ageCellChurned;
}

function AgeCellContent({
  cell,
  ageDays,
  currency,
}: {
  cell: RetentionAgeCell;
  ageDays: 30 | 60 | 90;
  currency: string;
}) {
  if (!cell.available) {
    const reason =
      cell.unavailableReason === "MATURITY_PENDING"
        ? `This cohort hasn't reached ${ageDays} days old yet.`
        : "Not available for this cohort.";
    return (
      <td
        className={`${overviewStyles.numeric} ${styles.ageCellUnavailable}`}
        title={reason}
      >
        Unavailable
      </td>
    );
  }
  return (
    <td
      className={`${overviewStyles.numeric} ${styles.ageCell} ${healthClass(cell.retentionPercentage)}`}
    >
      <span className={styles.ageCellValue}>
        {formatMoneyMinor(cell.retainedMrrMinor ?? 0, currency)}
      </span>
      <span className={styles.ageCellSub}>
        {cell.retentionPercentage === null
          ? "—"
          : `${Math.round(cell.retentionPercentage * 100)}% retained`}
        {cell.nrr === null ? "" : ` · NRR ${Math.round(cell.nrr * 100)}%`}
      </span>
    </td>
  );
}

export function CohortHeatmap({
  workspaceId,
  projectId,
  dimension,
  source,
  campaign,
  campaignMissing,
  onDrillDown,
}: CohortHeatmapProps) {
  const [cohorts, setCohorts] = useState<RetentionCohorts | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const filterKey = `${workspaceId}|${projectId}|${dimension}|${source ?? ""}|${campaign ?? ""}|${campaignMissing}`;
  const [loadedKey, setLoadedKey] = useState(filterKey);
  if (filterKey !== loadedKey) {
    setLoadedKey(filterKey);
    setLoading(true);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    getRetentionCohorts(client, workspaceId, projectId, dimension, {
      source: source ?? undefined,
      campaign: campaign ?? undefined,
      campaignMissing: campaignMissing || undefined,
    })
      .then((result) => {
        if (cancelled) return;
        setCohorts(result);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load retention cohorts. Try again.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, projectId, dimension, source, campaign, campaignMissing]);

  if (loading) {
    return <SkeletonBlock label="Loading retention cohorts" lines={5} />;
  }
  if (error) {
    return (
      <ErrorState
        title="Could not load retention cohorts"
        description={error}
      />
    );
  }
  if (!cohorts || cohorts.cohorts.length === 0) {
    return (
      <EmptyState
        title="No acquisition cohorts yet"
        description="No customers have been acquired for this dimension yet, so there is nothing to retain."
      />
    );
  }

  const rows: RetentionCohortRow[] = cohorts.cohorts;
  const hasImmatureCell = rows.some(
    (row) =>
      !row.age30.available || !row.age60.available || !row.age90.available,
  );

  return (
    <>
      <div className={styles.legend} aria-hidden="true">
        <span
          className={`${styles.legendSwatch} ${styles.legendSwatchHealthy}`}
        >
          100%+ retained
        </span>
        <span
          className={`${styles.legendSwatch} ${styles.legendSwatchModerate}`}
        >
          70–99%
        </span>
        <span className={`${styles.legendSwatch} ${styles.legendSwatchWeak}`}>
          1–69%
        </span>
        <span
          className={`${styles.legendSwatch} ${styles.legendSwatchChurned}`}
        >
          Fully churned
        </span>
      </div>

      <div className={overviewStyles.tableWrap}>
        <table className={overviewStyles.table}>
          <thead>
            <tr>
              <th scope="col">{dimensionLabel(dimension)}</th>
              <th scope="col">Acquisition period</th>
              <th scope="col">Currency</th>
              <th scope="col" className={overviewStyles.numeric}>
                Customers
              </th>
              <th scope="col" className={overviewStyles.numeric}>
                Starting MRR
              </th>
              <th scope="col" className={overviewStyles.numeric}>
                30 days
              </th>
              <th scope="col" className={overviewStyles.numeric}>
                60 days
              </th>
              <th scope="col" className={overviewStyles.numeric}>
                90 days
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const canDrillDown =
                dimension !== "LANDING_PAGE" && row.dimensionValue !== null;
              const canDrillNoEvidenceBucket =
                dimension === "CAMPAIGN" && row.dimensionValue === null;
              const isDrillable = canDrillDown || canDrillNoEvidenceBucket;
              return (
                <tr
                  key={`${row.dimensionValue ?? "none"}-${row.attributed}-${row.currency}-${row.periodStart}`}
                >
                  <td>
                    {row.dimensionValue === null ? (
                      dimension === "SOURCE" ? (
                        <StatusBadge tone="neutral">
                          {noEvidenceLabel(dimension, row.attributed)}
                        </StatusBadge>
                      ) : isDrillable ? (
                        <button
                          type="button"
                          className={sourcesStyles.dimensionButton}
                          onClick={() => onDrillDown(null)}
                        >
                          {noEvidenceLabel(dimension, row.attributed)}
                        </button>
                      ) : (
                        noEvidenceLabel(dimension, row.attributed)
                      )
                    ) : isDrillable ? (
                      <button
                        type="button"
                        className={sourcesStyles.dimensionButton}
                        onClick={() => onDrillDown(row.dimensionValue)}
                      >
                        {row.dimensionValue}
                      </button>
                    ) : (
                      row.dimensionValue
                    )}
                  </td>
                  <td>{formatPeriod(row.periodStart)}</td>
                  <td>{row.currency}</td>
                  <td className={overviewStyles.numeric}>{row.sampleSize}</td>
                  <td className={overviewStyles.numeric}>
                    {formatMoneyMinor(row.startingMrrMinor, row.currency)}
                  </td>
                  <AgeCellContent
                    cell={row.age30}
                    ageDays={30}
                    currency={row.currency}
                  />
                  <AgeCellContent
                    cell={row.age60}
                    ageDays={60}
                    currency={row.currency}
                  />
                  <AgeCellContent
                    cell={row.age90}
                    ageDays={90}
                    currency={row.currency}
                  />
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {hasImmatureCell ? (
        <p className={sourcesStyles.unavailableNote} role="note">
          &ldquo;Unavailable&rdquo; means that age hasn&rsquo;t matured yet for
          every customer in that cohort period -- never a fabricated zero. It
          becomes a real number automatically once the cohort is old enough.
        </p>
      ) : null}
    </>
  );
}
