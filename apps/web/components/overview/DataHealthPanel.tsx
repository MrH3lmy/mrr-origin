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
  /** Null when this signal couldn't be loaded -- rendered as its own "couldn't load" row rather than crashing the panel or the page. */
  coverage: AttributionCoverage | null;
  stripeHealth: StripeBillingHealthReport | null;
  diagnostics: ProjectDiagnosticsReport | null;
}

// Only used to decide whether this compact panel needs to draw attention at a glance -- each
// underlying status keeps its own already-defined tone; this never invents a new judgment about
// what counts as healthy. A signal that failed to load counts as "warning" here so a load failure
// can never be masked by an "All systems healthy" header.
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
  const trackingCopy = diagnostics
    ? DIAGNOSTIC_STATE_COPY[diagnostics.state]
    : null;
  const stripeCopy = stripeHealth
    ? STRIPE_HEALTH_STATUS_COPY[stripeHealth.status]
    : null;
  const coveragePercent = coverage
    ? Math.round(coverage.coverageRatio * 100)
    : null;
  const coverageTone: StatusTone | null = coverage
    ? coverage.attributedNewCustomers < coverage.eligibleNewCustomers
      ? "warning"
      : "positive"
    : null;

  const worstTone = [
    trackingCopy?.tone ?? "warning",
    stripeCopy?.tone ?? "warning",
    coverageTone ?? "warning",
  ].reduce(
    (worst, tone) =>
      TONE_SEVERITY[tone] > TONE_SEVERITY[worst] ? tone : worst,
    "positive" as StatusTone,
  );

  const exclusionEntries = coverage
    ? Object.entries(coverage.exclusionReasonCounts).filter(
        ([, count]) => count > 0,
      )
    : [];

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
              {trackingCopy?.headline ??
                "Couldn't load tracking status. Try refreshing."}
            </span>
          </div>
          <StatusBadge tone={trackingCopy?.tone ?? "warning"}>
            {trackingCopy?.label ?? "Unavailable"}
          </StatusBadge>
        </div>

        <div className={styles.healthRow}>
          <div className={styles.healthRowLabel}>
            <span className={styles.healthRowTitle}>Stripe sync</span>
            <span className={styles.healthRowDetail}>
              {stripeCopy?.headline ??
                "Couldn't load Stripe sync status. Try refreshing."}
            </span>
          </div>
          <StatusBadge tone={stripeCopy?.tone ?? "warning"}>
            {stripeCopy?.label ?? "Unavailable"}
          </StatusBadge>
        </div>

        <div className={styles.healthRow}>
          <div className={styles.healthRowLabel}>
            <span className={styles.healthRowTitle}>Attribution coverage</span>
            <span className={styles.healthRowDetail}>
              {coverage
                ? `${coverage.attributedNewCustomers} of ${coverage.eligibleNewCustomers} eligible new customers attributed`
                : "Couldn't load attribution coverage. Try refreshing."}
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
          <StatusBadge tone={coverageTone ?? "warning"}>
            {coveragePercent === null ? "Unavailable" : `${coveragePercent}%`}
          </StatusBadge>
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
