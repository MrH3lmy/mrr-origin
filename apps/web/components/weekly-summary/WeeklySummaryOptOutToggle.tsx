"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ui/Button";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import {
  getWeeklySummaryOptOut,
  setWeeklySummaryOptOut,
} from "@/lib/api/notifications";

import styles from "./WeeklySummary.module.css";

interface WeeklySummaryOptOutToggleProps {
  workspaceId: string;
  projectId: string;
}

/**
 * Authenticated, per-member subscription control for this project's weekly summary email (#59,
 * plan §3b). Every member manages their own subscription -- no manager privilege required, no
 * unauthenticated unsubscribe link in v1 (accepted B4).
 */
export function WeeklySummaryOptOutToggle({
  workspaceId,
  projectId,
}: WeeklySummaryOptOutToggleProps) {
  const [optedOut, setOptedOut] = useState<boolean | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    getWeeklySummaryOptOut(client, workspaceId, projectId)
      .then((result) => {
        if (!cancelled) setOptedOut(result.optedOut);
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load your subscription status.",
        );
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, projectId]);

  async function toggle() {
    if (optedOut === null || pending) return;
    setPending(true);
    setError(null);
    const next = !optedOut;
    try {
      const client = createBrowserClient();
      const result = await setWeeklySummaryOptOut(
        client,
        workspaceId,
        projectId,
        next,
      );
      setOptedOut(result.optedOut);
    } catch (toggleError) {
      setError(
        toggleError instanceof ApiError
          ? toggleError.message
          : "Could not update your subscription. Try again.",
      );
    } finally {
      setPending(false);
    }
  }

  if (optedOut === null && !error) {
    return <SkeletonBlock label="Loading subscription status" lines={1} />;
  }

  return (
    <div className={styles.optOutRow}>
      <div>
        <p className={styles.optOutLabel}>
          {optedOut
            ? "You are not receiving the weekly summary email for this project."
            : "You are receiving the weekly summary email for this project."}
        </p>
        {error ? <p className={styles.optOutError}>{error}</p> : null}
      </div>
      <Button
        variant="secondary"
        size="small"
        onClick={toggle}
        loading={pending}
        disabled={optedOut === null}
        aria-pressed={optedOut === false}
      >
        {optedOut ? "Resume email" : "Unsubscribe"}
      </Button>
    </div>
  );
}
