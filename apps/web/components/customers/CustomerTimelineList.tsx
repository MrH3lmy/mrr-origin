import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type { CustomerTimelineEntry, CustomerTimelineEventType } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatMoneyMinor } from "@/lib/format-currency";
import {
  ATTRIBUTION_EXCLUSION_REASON_COPY,
  MOVEMENT_TYPE_LABEL,
} from "@/lib/status-copy";

import styles from "./Customers.module.css";

const EVENT_TYPE_LABEL: Record<CustomerTimelineEventType, string> = {
  IDENTITY_LINK_CREATED: "Identity linked",
  IDENTITY_LINK_SUPERSEDED: "Identity link replaced",
  TOUCHPOINT_FIRST: "First touch",
  TOUCHPOINT_LAST: "Last touch",
  TOUCHPOINT_FIRST_AND_LAST: "First and last touch",
  SUBSCRIPTION_STATUS_CHANGED: "Subscription status changed",
  MRR_MOVEMENT: "MRR movement",
  ATTRIBUTION_CALCULATED: "Attribution calculated",
  REPAIR_AUDIT: "Repair applied",
};

const EVENT_TYPE_TONE: Record<CustomerTimelineEventType, StatusTone> = {
  IDENTITY_LINK_CREATED: "info",
  IDENTITY_LINK_SUPERSEDED: "warning",
  TOUCHPOINT_FIRST: "positive",
  TOUCHPOINT_LAST: "positive",
  TOUCHPOINT_FIRST_AND_LAST: "positive",
  SUBSCRIPTION_STATUS_CHANGED: "info",
  MRR_MOVEMENT: "positive",
  ATTRIBUTION_CALCULATED: "neutral",
  REPAIR_AUDIT: "warning",
};

interface CustomerTimelineListProps {
  entries: CustomerTimelineEntry[];
}

/** #24's chronological evidence timeline. Semantic `<ol>` so the sequence reads correctly to assistive tech. */
export function CustomerTimelineList({ entries }: CustomerTimelineListProps) {
  return (
    <ol className={styles.timeline} aria-label="Evidence timeline">
      {entries.map((entry) => (
        <li className={styles.timelineItem} key={`${entry.eventType}-${entry.referenceId}`}>
          <span className={styles.timelineDot} aria-hidden="true" />
          <div className={styles.timelineTime}>{formatDateTime(entry.at)}</div>
          <StatusBadge tone={EVENT_TYPE_TONE[entry.eventType]}>
            {EVENT_TYPE_LABEL[entry.eventType]}
          </StatusBadge>
          <p className={styles.timelineExplanation}>{entry.explanation}</p>
          <EntryDetail entry={entry} />
        </li>
      ))}
    </ol>
  );
}

function EntryDetail({ entry }: { entry: CustomerTimelineEntry }) {
  const parts: string[] = [];

  if (entry.movementType && entry.currency && entry.amountMinor !== null) {
    parts.push(
      `${MOVEMENT_TYPE_LABEL[entry.movementType]}: ${formatMoneyMinor(entry.amountMinor, entry.currency)}`,
    );
  }
  if (entry.confidence) {
    parts.push(
      entry.confidence === "STRONG"
        ? "Confidence: Strong"
        : `Confidence: Unattributed${
            entry.unattributedReason
              ? ` (${ATTRIBUTION_EXCLUSION_REASON_COPY[entry.unattributedReason] ?? entry.unattributedReason})`
              : ""
          }`,
    );
  }
  if (entry.previousStatus || entry.newStatus) {
    parts.push(`${entry.previousStatus ?? "unknown"} → ${entry.newStatus ?? "unknown"}`);
  }
  if (entry.externalUserId) {
    parts.push(`Application user: ${entry.externalUserId}`);
  }
  if (entry.actionType) {
    parts.push(`Action: ${entry.actionType}`);
  }

  if (parts.length === 0) return null;
  return <p className={styles.timelineMeta}>{parts.join(" · ")}</p>;
}
