"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { ExportLink } from "@/components/ui/ExportLink";
import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { comparisonExportUrl, getWeeklySummary } from "@/lib/api/reporting";
import type {
  WeeklySummary,
  WeeklySummaryInsight,
  WeeklySummaryInsightStatus,
} from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatMoneyMinor } from "@/lib/format-currency";

import styles from "./WeeklySummary.module.css";

interface WeeklySummaryClientProps {
  workspaceId: string;
  projectId: string;
}

const STATUS_TONE: Record<WeeklySummaryInsightStatus, StatusTone> = {
  MATERIAL_CHANGE: "warning",
  NEWLY_APPEARED: "positive",
  DISAPPEARED: "danger",
  INSUFFICIENT_SAMPLE: "neutral",
  STABLE: "neutral",
};

const STATUS_LABEL: Record<WeeklySummaryInsightStatus, string> = {
  MATERIAL_CHANGE: "Material change",
  NEWLY_APPEARED: "Newly appeared",
  DISAPPEARED: "Disappeared",
  INSUFFICIENT_SAMPLE: "Too few customers to compare",
  STABLE: "Stable",
};

function bucketLabel(insight: WeeklySummaryInsight): string {
  if (insight.dimensionValue !== null) return insight.dimensionValue;
  return insight.dimensionBucket === "NONE"
    ? "No value captured"
    : "Unattributed";
}

/** Relative-path evidence links resolve within this same app, so a plain Next `<Link>` is correct here. */
function EvidenceLinks({ insight }: { insight: WeeklySummaryInsight }) {
  return (
    <span className={styles.evidenceLinks}>
      <Link href={insight.currentEvidenceLink}>This week</Link>
      {" / "}
      <Link href={insight.priorEvidenceLink}>Prior week</Link>
    </span>
  );
}

function InsightRow({
  insight,
  currency,
}: {
  insight: WeeklySummaryInsight;
  currency: string;
}) {
  const movementLabel =
    insight.movementType === "NEW" ? "New MRR" : "Churned MRR";
  return (
    <li className={styles.insightRow}>
      <div className={styles.insightHeader}>
        <span className={styles.insightLabel}>
          {movementLabel} · {insight.dimension} · {bucketLabel(insight)}
        </span>
        <StatusBadge tone={STATUS_TONE[insight.status]}>
          {STATUS_LABEL[insight.status]}
        </StatusBadge>
      </div>
      <p className={styles.insightDetail}>
        {formatMoneyMinor(insight.currentAmountMinor, currency)} this week (
        {insight.currentCustomerCount} customers), was{" "}
        {formatMoneyMinor(insight.priorAmountMinor, currency)} (
        {insight.priorCustomerCount} customers) prior week.
        {insight.percentageChange !== null
          ? ` ${Math.round(insight.percentageChange * 100)}% change.`
          : ""}
      </p>
      <EvidenceLinks insight={insight} />
    </li>
  );
}

export function WeeklySummaryClient({
  workspaceId,
  projectId,
}: WeeklySummaryClientProps) {
  const [summary, setSummary] = useState<WeeklySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    getWeeklySummary(client, workspaceId, projectId)
      .then((result) => {
        if (cancelled) return;
        setSummary(result);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load the weekly summary. Try again.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, projectId]);

  if (loading) {
    return <SkeletonBlock label="Loading weekly summary" lines={6} />;
  }
  if (error) {
    return (
      <ErrorState
        title="Could not load the weekly summary"
        description={error}
      />
    );
  }
  if (!summary) {
    return null;
  }

  return (
    <div style={{ display: "grid", gap: 20 }}>
      <p className={styles.periodLabel}>
        Week of {formatDateTime(summary.weekStart)} to{" "}
        {formatDateTime(summary.weekEnd)} ({summary.timezone}), compared with
        the prior week ({formatDateTime(summary.priorWeekStart)} to{" "}
        {formatDateTime(summary.priorWeekEnd)}).
      </p>

      {summary.currencySections.length === 0 ? (
        <EmptyState
          title="Nothing to summarize yet"
          description="No New or Churned MRR was recorded in this or the prior completed week."
        />
      ) : (
        summary.currencySections.map((section) => {
          const actionable = section.insights.filter(
            (i) => i.status !== "STABLE",
          );
          const stableCount = section.insights.length - actionable.length;
          return (
            <Panel
              key={section.currency}
              title={section.currency}
              actions={
                <ExportLink
                  href={comparisonExportUrl(
                    workspaceId,
                    projectId,
                    summary.weekStart,
                    summary.weekEnd,
                    "SOURCE",
                  )}
                >
                  Export comparison CSV
                </ExportLink>
              }
            >
              {actionable.length === 0 ? (
                <p className={styles.allStable}>
                  Every comparison signal was stable this week.
                </p>
              ) : (
                <ul className={styles.insightList}>
                  {actionable.map((insight, index) => (
                    <InsightRow
                      key={index}
                      insight={insight}
                      currency={section.currency}
                    />
                  ))}
                </ul>
              )}
              {stableCount > 0 ? (
                <p className={styles.stableNote}>
                  {stableCount} other comparison signal
                  {stableCount === 1 ? "" : "s"} stable this week.
                </p>
              ) : null}
            </Panel>
          );
        })
      )}
    </div>
  );
}
