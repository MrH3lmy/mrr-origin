"use client";

import { useState } from "react";

import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { EmptyState, ErrorState } from "@/components/ui/StateMessage";
import { createBrowserClient } from "@/lib/api/client";
import { getCustomerTimeline } from "@/lib/api/customers";
import { ApiError } from "@/lib/api/errors";
import type { CustomerTimeline } from "@/lib/api/types";

import { CustomerOverviewPanel } from "./CustomerOverviewPanel";
import { CustomerTimelineList } from "./CustomerTimelineList";

interface CustomerTimelineClientProps {
  workspaceId: string;
  projectId: string;
  stripeCustomerId: string;
  initialTimeline: CustomerTimeline;
}

/**
 * Owns client-side state for #24's customer detail screen: the detail header/why-attribution/repair
 * panel (server-rendered on first load, refreshed in place after a successful repair) and the
 * paginated evidence timeline below it.
 */
export function CustomerTimelineClient({
  workspaceId,
  projectId,
  stripeCustomerId,
  initialTimeline,
}: CustomerTimelineClientProps) {
  const [timeline, setTimeline] = useState(initialTimeline);
  const [nextCursor, setNextCursor] = useState(initialTimeline.nextCursor);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    setError(null);
    try {
      const client = createBrowserClient();
      const page = await getCustomerTimeline(
        client,
        workspaceId,
        projectId,
        stripeCustomerId,
        {
          cursor: nextCursor,
        },
      );
      setTimeline((prev) => ({
        ...prev,
        entries: [...prev.entries, ...page.entries],
      }));
      setNextCursor(page.nextCursor);
    } catch (loadError) {
      setError(
        loadError instanceof ApiError
          ? loadError.message
          : "Could not load more of the timeline. Try again.",
      );
    } finally {
      setLoadingMore(false);
    }
  }

  function onRepaired(refreshed: CustomerTimeline) {
    setTimeline(refreshed);
    setNextCursor(refreshed.nextCursor);
  }

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <CustomerOverviewPanel
        workspaceId={workspaceId}
        projectId={projectId}
        detail={timeline.detail}
        onRepaired={onRepaired}
      />

      <Panel
        title="Evidence timeline"
        subtitle="Every acquisition touch, identity link, subscription change, MRR movement, attribution calculation, and repair, in order."
      >
        {timeline.entries.length === 0 ? (
          <EmptyState
            title="No timeline events yet"
            description="Events appear here as this customer's identity, subscriptions, and MRR change."
          />
        ) : (
          <>
            <CustomerTimelineList entries={timeline.entries} />
            {error ? (
              <div style={{ marginTop: 12 }}>
                <ErrorState title="Could not load more" description={error} />
              </div>
            ) : null}
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
    </div>
  );
}
