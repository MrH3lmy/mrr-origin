"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { listMrrMovements } from "@/lib/api/reporting";
import type { MrrMovementEntry, MrrMovementType } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatMoneyMinor } from "@/lib/format-currency";
import {
  ATTRIBUTION_EXCLUSION_REASON_COPY,
  MOVEMENT_TYPE_LABEL,
} from "@/lib/status-copy";

import styles from "./Overview.module.css";

interface MovementsDrilldownProps {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
  movementType: MrrMovementType | null;
  source: string | null;
  /** Requires `source`. #23's source -> campaign -> landing page drill-down. */
  campaign?: string | null;
  /** Requires `campaign`. */
  landingPage?: string | null;
  currency: string | null;
  onClearFilters: () => void;
}

export function MovementsDrilldown({
  workspaceId,
  projectId,
  from,
  to,
  movementType,
  source,
  campaign = null,
  landingPage = null,
  currency,
  onClearFilters,
}: MovementsDrilldownProps) {
  const [entries, setEntries] = useState<MrrMovementEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Render-phase reset when the filter/period params change -- React's recommended alternative to
  // an effect that only mirrors props (same pattern AppShell uses for its pathname-keyed drawer
  // reset), so the pending fetch below only ever calls setState from its async continuation.
  const filterKey = `${workspaceId}|${projectId}|${from}|${to}|${movementType ?? ""}|${source ?? ""}|${campaign ?? ""}|${landingPage ?? ""}|${currency ?? ""}`;
  const [loadedKey, setLoadedKey] = useState(filterKey);
  if (filterKey !== loadedKey) {
    setLoadedKey(filterKey);
    setLoading(true);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    listMrrMovements(client, workspaceId, projectId, from, to, {
      movementType: movementType ?? undefined,
      source: source ?? undefined,
      campaign: campaign ?? undefined,
      landingPage: landingPage ?? undefined,
      currency: currency ?? undefined,
    })
      .then((page) => {
        if (cancelled) return;
        setEntries(page.entries);
        setNextCursor(page.nextCursor);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load movements. Try again.",
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
    movementType,
    source,
    campaign,
    landingPage,
    currency,
  ]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const client = createBrowserClient();
      const page = await listMrrMovements(
        client,
        workspaceId,
        projectId,
        from,
        to,
        {
          movementType: movementType ?? undefined,
          source: source ?? undefined,
          campaign: campaign ?? undefined,
          landingPage: landingPage ?? undefined,
          currency: currency ?? undefined,
          cursor: nextCursor,
        },
      );
      setEntries((prev) => [...prev, ...page.entries]);
      setNextCursor(page.nextCursor);
    } catch (loadError) {
      setError(
        loadError instanceof ApiError
          ? loadError.message
          : "Could not load more movements. Try again.",
      );
    } finally {
      setLoadingMore(false);
    }
  }

  const hasFilters = Boolean(
    movementType || source || campaign || landingPage || currency,
  );

  return (
    <Panel
      title="Movement evidence"
      subtitle="Every summarized number above drills down to the customers and evidence behind it."
    >
      {hasFilters ? (
        <div className={styles.filterBar}>
          {movementType ? (
            <span className={styles.filterChip}>
              {MOVEMENT_TYPE_LABEL[movementType]}
            </span>
          ) : null}
          {source ? (
            <span className={styles.filterChip}>
              {source === "UNATTRIBUTED" ? "Unattributed" : source}
            </span>
          ) : null}
          {campaign ? (
            <span className={styles.filterChip}>
              {campaign === "NONE" ? "No campaign" : campaign}
            </span>
          ) : null}
          {landingPage ? (
            <span className={styles.filterChip}>
              {landingPage === "NONE" ? "No landing page" : landingPage}
            </span>
          ) : null}
          {currency ? (
            <span className={styles.filterChip}>{currency}</span>
          ) : null}
          <Button variant="ghost" size="small" onClick={onClearFilters}>
            Clear filters
          </Button>
        </div>
      ) : null}

      {loading ? (
        <SkeletonBlock label="Loading movements" lines={4} />
      ) : error ? (
        <ErrorState title="Could not load movements" description={error} />
      ) : entries.length === 0 ? (
        <EmptyState
          title="No movements match this view"
          description={
            hasFilters
              ? "Try clearing filters or choosing a different period."
              : "Nothing happened to MRR in this period yet."
          }
        />
      ) : (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col">Type</th>
                  <th scope="col">Effective</th>
                  <th scope="col" className={styles.numeric}>
                    Amount
                  </th>
                  <th scope="col">Evidence</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.movementId}>
                    <td>{entry.stripeCustomerId}</td>
                    <td>{MOVEMENT_TYPE_LABEL[entry.movementType]}</td>
                    <td>{formatDateTime(entry.effectiveAt)}</td>
                    <td className={styles.numeric}>
                      {formatMoneyMinor(entry.amountMinor, entry.currency)}
                    </td>
                    <td className={styles.wrap}>
                      {entry.confidence === "STRONG" ? (
                        <>
                          <StatusBadge tone="positive">
                            {entry.firstTouch?.source ?? "Attributed"}
                          </StatusBadge>
                          {entry.firstTouch?.campaign ? (
                            <p className={styles.evidenceDetail}>
                              Campaign: {entry.firstTouch.campaign}
                            </p>
                          ) : null}
                        </>
                      ) : (
                        <>
                          <StatusBadge tone="neutral">Unattributed</StatusBadge>
                          {entry.unattributedReason ? (
                            <p className={styles.evidenceDetail}>
                              {ATTRIBUTION_EXCLUSION_REASON_COPY[
                                entry.unattributedReason
                              ] ?? entry.unattributedReason}
                            </p>
                          ) : null}
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {nextCursor ? (
            <div style={{ marginTop: 12 }}>
              <Button
                variant="secondary"
                size="small"
                onClick={loadMore}
                loading={loadingMore}
              >
                Load more
              </Button>
            </div>
          ) : null}
        </>
      )}
    </Panel>
  );
}
