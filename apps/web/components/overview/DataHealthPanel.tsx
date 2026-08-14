import { ButtonLink } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type {
  AttributionCoverage,
  ProjectDiagnosticsReport,
  StripeBillingHealthReport,
} from "@/lib/api/types";
import {
  ATTRIBUTION_EXCLUSION_REASON_COPY,
  DIAGNOSTIC_STATE_COPY,
  STRIPE_HEALTH_STATUS_COPY,
} from "@/lib/status-copy";

import styles from "./Overview.module.css";

interface DataHealthPanelProps {
  workspaceId: string;
  projectId: string;
  coverage: AttributionCoverage;
  stripeHealth: StripeBillingHealthReport;
  diagnostics: ProjectDiagnosticsReport;
}

// Only used to decide whether this compact panel needs to draw attention at a glance -- each
// underlying status keeps its own already-defined tone; this never invents a new judgment about
// what counts as healthy.
const TONE_SEVERITY: Record<StatusTone, number> = {
  danger: 3,
  warning: 2,
  info: 1,
  neutral: 1,
  positive: 0,
};

export function DataHealthPanel({
  workspaceId,
  projectId,
  coverage,
  stripeHealth,
  diagnostics,
}: DataHealthPanelProps) {
  const stripeCopy = STRIPE_HEALTH_STATUS_COPY[stripeHealth.status];
  const trackingCopy = DIAGNOSTIC_STATE_COPY[diagnostics.state];
  const coveragePercent = Math.round(coverage.coverageRatio * 100);
  const hasCoverageGap =
    coverage.attributedNewCustomers < coverage.eligibleNewCustomers;
  const coverageTone: StatusTone = hasCoverageGap ? "warning" : "positive";

  const worstTone = [stripeCopy.tone, trackingCopy.tone, coverageTone].reduce(
    (worst, tone) =>
      TONE_SEVERITY[tone] > TONE_SEVERITY[worst] ? tone : worst,
    "positive" as StatusTone,
  );

  const exclusionEntries = Object.entries(
    coverage.exclusionReasonCounts,
  ).filter(([, count]) => count > 0);

  return (
    <Panel
      title="Data health"
      subtitle="Tracking, Stripe sync, and attribution coverage for this project."
      actions={
        <StatusBadge tone={worstTone}>
          {worstTone === "positive" ? "All systems healthy" : "Needs attention"}
        </StatusBadge>
      }
    >
      <div>
        <div className={styles.healthRow}>
          <div className={styles.healthRowLabel}>
            <span className={styles.healthRowTitle}>Tracking</span>
            <span className={styles.healthRowDetail}>
              {trackingCopy.headline}
            </span>
          </div>
          <StatusBadge tone={trackingCopy.tone}>
            {trackingCopy.label}
          </StatusBadge>
        </div>

        <div className={styles.healthRow}>
          <div className={styles.healthRowLabel}>
            <span className={styles.healthRowTitle}>Stripe sync</span>
            <span className={styles.healthRowDetail}>
              {stripeCopy.headline}
            </span>
          </div>
          <StatusBadge tone={stripeCopy.tone}>{stripeCopy.label}</StatusBadge>
        </div>

        <div className={styles.healthRow}>
          <div className={styles.healthRowLabel}>
            <span className={styles.healthRowTitle}>Attribution coverage</span>
            <span className={styles.healthRowDetail}>
              {coverage.attributedNewCustomers} of{" "}
              {coverage.eligibleNewCustomers} eligible new customers attributed
            </span>
            {exclusionEntries.length > 0 ? (
              <ul className={styles.reasonList}>
                {exclusionEntries.map(([reason, count]) => (
                  <li key={reason}>
                    <span>
                      {ATTRIBUTION_EXCLUSION_REASON_COPY[reason] ?? reason}
                    </span>
                    <span>{count}</span>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
          <StatusBadge tone={coverageTone}>{`${coveragePercent}%`}</StatusBadge>
        </div>
      </div>

      <div style={{ marginTop: 16 }}>
        <ButtonLink
          variant="secondary"
          size="small"
          href={`/app/${workspaceId}/projects/${projectId}`}
        >
          Open Data health
        </ButtonLink>
      </div>
    </Panel>
  );
}
