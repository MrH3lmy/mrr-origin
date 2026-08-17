"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/Button";
import { ExportLink } from "@/components/ui/ExportLink";
import { Field } from "@/components/ui/Field";
import { Panel } from "@/components/ui/Panel";
import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { listCustomers } from "@/lib/api/customers";
import { ApiError } from "@/lib/api/errors";
import { customersExportUrl } from "@/lib/api/reporting";
import type { CustomerDirectoryEntry } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { formatMoneyMinor } from "@/lib/format-currency";
import {
  ATTRIBUTION_EXCLUSION_REASON_COPY,
  SUBSCRIPTION_STATUS_LABEL,
  SUBSCRIPTION_STATUS_TONE,
} from "@/lib/status-copy";

import styles from "./Customers.module.css";

interface CustomersClientProps {
  workspaceId: string;
  projectId: string;
}

export function CustomersClient({
  workspaceId,
  projectId,
}: CustomersClientProps) {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [entries, setEntries] = useState<CustomerDirectoryEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Render-phase reset when the search term changes -- same idiom as MovementsDrilldown's filterKey.
  const [loadedSearch, setLoadedSearch] = useState(search);
  if (search !== loadedSearch) {
    setLoadedSearch(search);
    setLoading(true);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    listCustomers(client, workspaceId, projectId, {
      search: search || undefined,
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
            : "Could not load customers. Try again.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, projectId, search]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const client = createBrowserClient();
      const page = await listCustomers(client, workspaceId, projectId, {
        search: search || undefined,
        cursor: nextCursor,
      });
      setEntries((prev) => [...prev, ...page.entries]);
      setNextCursor(page.nextCursor);
    } catch (loadError) {
      setError(
        loadError instanceof ApiError
          ? loadError.message
          : "Could not load more customers. Try again.",
      );
    } finally {
      setLoadingMore(false);
    }
  }

  function onSubmitSearch(event: FormEvent) {
    event.preventDefault();
    setSearch(searchInput.trim());
  }

  return (
    <Panel
      title="Customers"
      subtitle="Every Stripe customer this project owns, with acquisition and current MRR at a glance."
      actions={
        <ExportLink href={customersExportUrl(workspaceId, projectId)}>
          Export CSV
        </ExportLink>
      }
    >
      <form
        className={styles.searchRow}
        onSubmit={onSubmitSearch}
        role="search"
      >
        <div className={styles.searchField}>
          <Field
            label="Search by Stripe customer ID"
            placeholder="cus_..."
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
          />
        </div>
        <Button type="submit" variant="secondary">
          Search
        </Button>
        {search ? (
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              setSearchInput("");
              setSearch("");
            }}
          >
            Clear
          </Button>
        ) : null}
      </form>

      {loading ? (
        <SkeletonBlock label="Loading customers" lines={5} />
      ) : error ? (
        <ErrorState title="Could not load customers" description={error} />
      ) : entries.length === 0 ? (
        <EmptyState
          title={search ? "No customers match this search" : "No customers yet"}
          description={
            search
              ? "Try a different Stripe customer ID."
              : "Customers appear here once Stripe billing data and identity links associate them with this project."
          }
        />
      ) : (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col">Acquisition</th>
                  <th scope="col">Subscription</th>
                  <th scope="col" className={styles.numeric}>
                    Current MRR
                  </th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.stripeCustomerId}>
                    <td>
                      <Link
                        href={`/app/${workspaceId}/projects/${projectId}/customers/${encodeURIComponent(entry.stripeCustomerId)}`}
                        className={styles.customerLink}
                      >
                        {entry.stripeCustomerId}
                      </Link>
                      {entry.acquisitionEffectiveAt ? (
                        <p className={styles.evidenceDetail}>
                          Customer since{" "}
                          {formatDateTime(entry.acquisitionEffectiveAt)}
                        </p>
                      ) : null}
                    </td>
                    <td className={styles.wrap}>
                      {entry.confidence === "STRONG" ? (
                        <div className={styles.badgeStack}>
                          <StatusBadge tone="positive">
                            {entry.firstSource ?? "Attributed"}
                          </StatusBadge>
                        </div>
                      ) : entry.confidence === "UNATTRIBUTED" ? (
                        <div className={styles.badgeStack}>
                          <StatusBadge tone="neutral">Unattributed</StatusBadge>
                          {entry.unattributedReason ? (
                            <p className={styles.evidenceDetail}>
                              {ATTRIBUTION_EXCLUSION_REASON_COPY[
                                entry.unattributedReason
                              ] ?? entry.unattributedReason}
                            </p>
                          ) : null}
                        </div>
                      ) : (
                        <StatusBadge tone="info">
                          Not yet calculated
                        </StatusBadge>
                      )}
                    </td>
                    <td>
                      {entry.subscriptionStatuses.length === 0 ? (
                        <span className={styles.evidenceDetail}>
                          No subscriptions
                        </span>
                      ) : (
                        <div className={styles.badgeStack}>
                          {entry.subscriptionStatuses.map((status) => (
                            <StatusBadge
                              key={status}
                              tone={
                                SUBSCRIPTION_STATUS_TONE[
                                  status as keyof typeof SUBSCRIPTION_STATUS_TONE
                                ] ?? "neutral"
                              }
                            >
                              {SUBSCRIPTION_STATUS_LABEL[
                                status as keyof typeof SUBSCRIPTION_STATUS_LABEL
                              ] ?? status}
                            </StatusBadge>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className={styles.numeric}>
                      {entry.currentMrr.length === 0
                        ? "—"
                        : entry.currentMrr.map((mrr) => (
                            <div key={mrr.currency}>
                              {formatMoneyMinor(mrr.amountMinor, mrr.currency)}
                            </div>
                          ))}
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
