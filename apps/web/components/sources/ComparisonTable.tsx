"use client";

import { useEffect, useState } from "react";

import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { getSourceComparison } from "@/lib/api/reporting";
import type {
  ComparisonDimension,
  ComparisonRow,
  SourceComparison,
} from "@/lib/api/types";
import { formatMoneyMinor } from "@/lib/format-currency";

import overviewStyles from "../overview/Overview.module.css";
import styles from "./Sources.module.css";

export interface MetricSelection {
  dimensionValue: string | null;
  /** Only meaningful when dimensionValue is null: distinguishes the Unattributed bucket (false) from
   * the strongly-attributed-but-no-value-captured bucket (true). See `ComparisonRow.attributed`. */
  attributed: boolean;
  movementType: "NEW" | "CHURN";
  currency: string;
}

interface ComparisonTableProps {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  dimension: ComparisonDimension;
  /** Parent filter, required for CAMPAIGN/LANDING_PAGE. */
  source: string | null;
  /** Parent filter for LANDING_PAGE. Mutually exclusive with `campaignMissing`. */
  campaign: string | null;
  /** Selects the "no campaign captured" bucket explicitly, rather than a sentinel value in `campaign`. */
  campaignMissing: boolean;
  /** dimensionValue is null for the "no campaign"/"no landing page" bucket at CAMPAIGN/LANDING_PAGE. */
  onDrillDown: (dimensionValue: string | null) => void;
  onSelectMetric: (selection: MetricSelection) => void;
  selectedMetric: MetricSelection | null;
}

interface PivotedRow {
  dimensionValue: string | null;
  /** See `ComparisonRow.attributed`. Part of the grouping key so the Unattributed bucket and the
   * no-value-captured bucket (both dimensionValue: null) never merge into one row. */
  attributed: boolean;
  currency: string;
  newTotalMinor: number;
  newCustomerCount: number;
  churnedTotalMinor: number;
  churnedCustomerCount: number;
}

function pivot(rows: ComparisonRow[]): PivotedRow[] {
  const byKey = new Map<string, PivotedRow>();
  for (const row of rows) {
    const key = `${row.dimensionValue ?? " "}|${row.attributed}|${row.currency}`;
    const existing = byKey.get(key) ?? {
      dimensionValue: row.dimensionValue,
      attributed: row.attributed,
      currency: row.currency,
      newTotalMinor: 0,
      newCustomerCount: 0,
      churnedTotalMinor: 0,
      churnedCustomerCount: 0,
    };
    if (row.movementType === "NEW") {
      existing.newTotalMinor = row.totalMinor;
      existing.newCustomerCount = row.customerCount;
    } else {
      existing.churnedTotalMinor = row.totalMinor;
      existing.churnedCustomerCount = row.customerCount;
    }
    byKey.set(key, existing);
  }
  return Array.from(byKey.values());
}

type SortKey = "dimension" | "newMrr" | "churnedMrr" | "customers";
type SortDirection = "asc" | "desc";

function sortRows(
  rows: PivotedRow[],
  key: SortKey,
  direction: SortDirection,
): PivotedRow[] {
  const factor = direction === "asc" ? 1 : -1;
  // Array.prototype.sort is a stable sort in every JS engine this app targets, and the
  // dimensionValue tiebreaker matches the API's own deterministic secondary order, so re-sorting
  // (or re-fetching) never silently swaps the position of two equal-value rows.
  return [...rows].sort((a, b) => {
    let primary = 0;
    if (key === "dimension") {
      primary = (a.dimensionValue ?? "").localeCompare(b.dimensionValue ?? "");
    } else if (key === "newMrr") {
      primary = a.newTotalMinor - b.newTotalMinor;
    } else if (key === "churnedMrr") {
      primary = a.churnedTotalMinor - b.churnedTotalMinor;
    } else {
      primary = a.newCustomerCount - b.newCustomerCount;
    }
    if (primary !== 0) return primary * factor;
    const byDimension = (a.dimensionValue ?? "").localeCompare(
      b.dimensionValue ?? "",
    );
    if (byDimension !== 0) return byDimension;
    // Tiebreaker for the two distinct dimensionValue===null buckets (Unattributed vs. no-value-
    // captured): without this, their relative order would be undefined.
    return Number(a.attributed) - Number(b.attributed);
  });
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

/**
 * Label for a dimensionValue===null row. At SOURCE, `attributed` distinguishes two different
 * buckets: `false` is genuinely no evidence (Unattributed); `true` is real customer-link/touchpoint
 * evidence with no source string captured (e.g. direct traffic). CAMPAIGN/LANDING_PAGE rows are
 * always attributed, so their null bucket only ever means "this field wasn't captured."
 */
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

export function ComparisonTable({
  workspaceId,
  projectId,
  from,
  to,
  dimension,
  source,
  campaign,
  campaignMissing,
  onDrillDown,
  onSelectMetric,
  selectedMetric,
}: ComparisonTableProps) {
  const [comparison, setComparison] = useState<SourceComparison | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection }>({
    key: "newMrr",
    direction: "desc",
  });

  const filterKey = `${workspaceId}|${projectId}|${from}|${to}|${dimension}|${source ?? ""}|${campaign ?? ""}|${campaignMissing}`;
  const [loadedKey, setLoadedKey] = useState(filterKey);
  if (filterKey !== loadedKey) {
    setLoadedKey(filterKey);
    setLoading(true);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    getSourceComparison(client, workspaceId, projectId, from, to, dimension, {
      source: source ?? undefined,
      campaign: campaign ?? undefined,
      campaignMissing: campaignMissing || undefined,
    })
      .then((result) => {
        if (cancelled) return;
        setComparison(result);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load the comparison. Try again.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [
    workspaceId,
    projectId,
    from,
    to,
    dimension,
    source,
    campaign,
    campaignMissing,
  ]);

  if (loading) {
    return <SkeletonBlock label="Loading comparison" lines={4} />;
  }
  if (error) {
    return (
      <ErrorState title="Could not load the comparison" description={error} />
    );
  }
  if (!comparison || comparison.rows.length === 0) {
    return (
      <EmptyState
        title="Nothing to compare in this period"
        description="No New or Churned MRR was recorded for this dimension in the selected period yet."
      />
    );
  }

  const currencies = Array.from(
    new Set(comparison.rows.map((r) => r.currency)),
  ).sort();

  function reasonFor(metric: string): string | undefined {
    return comparison?.unavailableMetrics.find((m) => m.metric === metric)
      ?.reason;
  }

  function toggleSort(key: SortKey) {
    setSort((current) =>
      current.key === key
        ? { key, direction: current.direction === "desc" ? "asc" : "desc" }
        : { key, direction: key === "dimension" ? "asc" : "desc" },
    );
  }

  function ariaSort(key: SortKey): "ascending" | "descending" | "none" {
    if (sort.key !== key) return "none";
    return sort.direction === "asc" ? "ascending" : "descending";
  }

  function SortHeader({
    label,
    sortKey,
    numeric = false,
  }: {
    label: string;
    sortKey: SortKey;
    numeric?: boolean;
  }) {
    return (
      <th
        scope="col"
        className={numeric ? overviewStyles.numeric : undefined}
        aria-sort={ariaSort(sortKey)}
      >
        <button
          type="button"
          className={styles.sortButton}
          onClick={() => toggleSort(sortKey)}
        >
          {label}
          <span className={styles.sortIndicator} aria-hidden="true">
            {sort.key === sortKey ? (sort.direction === "asc" ? "↑" : "↓") : ""}
          </span>
        </button>
      </th>
    );
  }

  return (
    <>
      {currencies.map((currency) => {
        const rows = sortRows(
          pivot(comparison.rows.filter((r) => r.currency === currency)),
          sort.key,
          sort.direction,
        );
        return (
          <div key={currency} style={{ marginBottom: 20 }}>
            {currencies.length > 1 ? (
              <p className={overviewStyles.currencyGroupLabel}>{currency}</p>
            ) : null}
            <div className={overviewStyles.tableWrap}>
              <table className={overviewStyles.table}>
                <thead>
                  <tr>
                    <SortHeader
                      label={dimensionLabel(dimension)}
                      sortKey="dimension"
                    />
                    <SortHeader label="New MRR" sortKey="newMrr" numeric />
                    <SortHeader
                      label="Churned MRR"
                      sortKey="churnedMrr"
                      numeric
                    />
                    <SortHeader label="Customers" sortKey="customers" numeric />
                    <th scope="col" className={overviewStyles.numeric}>
                      Retained MRR
                    </th>
                    <th scope="col" className={overviewStyles.numeric}>
                      NRR
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => {
                    const canDrillDown =
                      dimension !== "LANDING_PAGE" &&
                      row.dimensionValue !== null;
                    const canDrillNoEvidenceBucket =
                      dimension === "CAMPAIGN" && row.dimensionValue === null;
                    const isDrillable =
                      canDrillDown || canDrillNoEvidenceBucket;
                    return (
                      <tr
                        key={`${row.dimensionValue ?? "none"}-${row.attributed}-${row.currency}`}
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
                                className={styles.dimensionButton}
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
                              className={styles.dimensionButton}
                              onClick={() => onDrillDown(row.dimensionValue)}
                            >
                              {row.dimensionValue}
                            </button>
                          ) : (
                            row.dimensionValue
                          )}
                        </td>
                        <td className={overviewStyles.numeric}>
                          <button
                            type="button"
                            className={styles.metricButton}
                            aria-pressed={
                              selectedMetric?.dimensionValue ===
                                row.dimensionValue &&
                              selectedMetric?.attributed === row.attributed &&
                              selectedMetric?.movementType === "NEW" &&
                              selectedMetric?.currency === row.currency
                            }
                            onClick={() =>
                              onSelectMetric({
                                dimensionValue: row.dimensionValue,
                                attributed: row.attributed,
                                movementType: "NEW",
                                currency: row.currency,
                              })
                            }
                            disabled={row.newTotalMinor === 0}
                          >
                            {formatMoneyMinor(row.newTotalMinor, row.currency)}
                          </button>
                        </td>
                        <td className={overviewStyles.numeric}>
                          <button
                            type="button"
                            className={styles.metricButton}
                            aria-pressed={
                              selectedMetric?.dimensionValue ===
                                row.dimensionValue &&
                              selectedMetric?.attributed === row.attributed &&
                              selectedMetric?.movementType === "CHURN" &&
                              selectedMetric?.currency === row.currency
                            }
                            onClick={() =>
                              onSelectMetric({
                                dimensionValue: row.dimensionValue,
                                attributed: row.attributed,
                                movementType: "CHURN",
                                currency: row.currency,
                              })
                            }
                            disabled={row.churnedTotalMinor === 0}
                          >
                            {formatMoneyMinor(
                              row.churnedTotalMinor,
                              row.currency,
                            )}
                          </button>
                        </td>
                        <td className={overviewStyles.numeric}>
                          {row.newCustomerCount}
                        </td>
                        <td
                          className={`${overviewStyles.numeric} ${styles.unavailableCell}`}
                          title={reasonFor("RETAINED_MRR")}
                        >
                          Unavailable
                        </td>
                        <td
                          className={`${overviewStyles.numeric} ${styles.unavailableCell}`}
                          title={reasonFor("NRR")}
                        >
                          Unavailable
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}

      {comparison.unavailableMetrics.length > 0 ? (
        <p className={styles.unavailableNote} role="note">
          {comparison.unavailableMetrics
            .map((metric) => metric.reason)
            .join(" ")}
        </p>
      ) : null}
    </>
  );
}
