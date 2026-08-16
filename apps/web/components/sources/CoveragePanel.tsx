import { ButtonLink } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type { AttributionCoverage } from "@/lib/api/types";
import { ATTRIBUTION_EXCLUSION_REASON_COPY } from "@/lib/status-copy";

import overviewStyles from "../overview/Overview.module.css";

interface CoveragePanelProps {
  workspaceId: string;
  projectId: string;
  /** Null when this signal couldn't be loaded -- rendered as its own "couldn't load" row rather than crashing the page. */
  coverage: AttributionCoverage | null;
}

/**
 * #23's "coverage" comparison column: the same project-level {@code AttributionCoverage} read
 * model #22's Data health panel already shows, not a per-row invention -- the approved plan for
 * this issue explicitly reuses that existing coverage read model rather than computing a new
 * per-source/campaign/page coverage metric.
 */
export function CoveragePanel({
  workspaceId,
  projectId,
  coverage,
}: CoveragePanelProps) {
  const coveragePercent = coverage
    ? Math.round(coverage.coverageRatio * 100)
    : null;
  const coverageTone: StatusTone = coverage
    ? coverage.attributedNewCustomers < coverage.eligibleNewCustomers
      ? "warning"
      : "positive"
    : "warning";
  const exclusionEntries = coverage
    ? Object.entries(coverage.exclusionReasonCounts).filter(
        ([, count]) => count > 0,
      )
    : [];

  return (
    <Panel
      title="Attribution coverage"
      subtitle="How much of the New MRR above is backed by acceptable acquisition evidence, project-wide."
    >
      <div className={overviewStyles.healthRow}>
        <div className={overviewStyles.healthRowLabel}>
          <span className={overviewStyles.healthRowTitle}>
            Eligible new customers attributed
          </span>
          <span className={overviewStyles.healthRowDetail}>
            {coverage
              ? `${coverage.attributedNewCustomers} of ${coverage.eligibleNewCustomers} eligible new customers attributed`
              : "Couldn't load attribution coverage. Try refreshing."}
          </span>
          {exclusionEntries.length > 0 ? (
            <ul className={overviewStyles.reasonList}>
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
        <StatusBadge tone={coverageTone}>
          {coveragePercent === null ? "Unavailable" : `${coveragePercent}%`}
        </StatusBadge>
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
