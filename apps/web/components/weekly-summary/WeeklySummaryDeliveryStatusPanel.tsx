"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { SkeletonBlock } from "@/components/ui/Skeleton";
import { EmptyState } from "@/components/ui/StateMessage";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import {
  listWeeklySummaryDeliveries,
  replayWeeklySummaryDelivery,
  sendWeeklySummaryNow,
} from "@/lib/api/notifications";
import type {
  WeeklySummaryDelivery,
  WeeklySummaryDeliveryStatus,
} from "@/lib/api/types";

import styles from "./WeeklySummary.module.css";

/** `weekStart` is a plain date (no time component) -- dateStyle only, unlike the shared formatDateTime. */
function formatWeekStart(iso: string): string {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium" }).format(
    new Date(iso),
  );
}

interface WeeklySummaryDeliveryStatusPanelProps {
  workspaceId: string;
  projectId: string;
}

const STATUS_TONE: Record<WeeklySummaryDeliveryStatus, StatusTone> = {
  PENDING: "info",
  SENDING: "info",
  SENT: "positive",
  FAILED: "warning",
  PERMANENTLY_FAILED: "danger",
  BLOCKED_MISSING_EMAIL: "warning",
};

const STATUS_LABEL: Record<WeeklySummaryDeliveryStatus, string> = {
  PENDING: "Pending",
  SENDING: "Sending",
  SENT: "Sent",
  FAILED: "Retrying",
  PERMANENTLY_FAILED: "Permanently failed",
  BLOCKED_MISSING_EMAIL: "No verified email",
};

function replayable(status: WeeklySummaryDeliveryStatus): boolean {
  return status === "PERMANENTLY_FAILED" || status === "BLOCKED_MISSING_EMAIL";
}

/**
 * Manager-only delivery status + manual send/replay for this project's weekly summary (#59, plan
 * §4d). The list endpoint is manager-only server-side -- this hides itself entirely on a 403 rather
 * than inventing separate role plumbing just to decide whether to render.
 */
export function WeeklySummaryDeliveryStatusPanel({
  workspaceId,
  projectId,
}: WeeklySummaryDeliveryStatusPanelProps) {
  const [deliveries, setDeliveries] = useState<WeeklySummaryDelivery[] | null>(
    null,
  );
  const [visible, setVisible] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const client = createBrowserClient();
    listWeeklySummaryDeliveries(client, workspaceId, projectId)
      .then((result) => {
        if (!cancelled) setDeliveries(result);
      })
      .catch((loadError) => {
        if (cancelled) return;
        if (loadError instanceof ApiError && loadError.status === 403) {
          setVisible(false);
          return;
        }
        setError(
          loadError instanceof ApiError
            ? loadError.message
            : "Could not load delivery status. Try again.",
        );
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, projectId, reloadToken]);

  function reload() {
    setLoading(true);
    setError(null);
    setReloadToken((token) => token + 1);
  }

  async function sendNow() {
    setPendingAction("send");
    setError(null);
    try {
      const client = createBrowserClient();
      await sendWeeklySummaryNow(client, workspaceId, projectId);
      reload();
    } catch (sendError) {
      setError(
        sendError instanceof ApiError
          ? sendError.message
          : "Could not trigger the weekly summary. Try again.",
      );
    } finally {
      setPendingAction(null);
    }
  }

  async function replay(deliveryId: string) {
    setPendingAction(deliveryId);
    setError(null);
    try {
      const client = createBrowserClient();
      await replayWeeklySummaryDelivery(
        client,
        workspaceId,
        projectId,
        deliveryId,
      );
      reload();
    } catch (replayError) {
      setError(
        replayError instanceof ApiError
          ? replayError.message
          : "Could not replay this delivery. Try again.",
      );
    } finally {
      setPendingAction(null);
    }
  }

  if (!visible) {
    return null;
  }

  const sendNowAction = (
    <Button
      variant="secondary"
      size="small"
      onClick={sendNow}
      loading={pendingAction === "send"}
      disabled={pendingAction !== null}
    >
      Send now
    </Button>
  );

  if (loading && deliveries === null) {
    return (
      <Panel title="Delivery status" actions={sendNowAction}>
        <SkeletonBlock label="Loading delivery status" lines={3} />
      </Panel>
    );
  }

  return (
    <Panel title="Delivery status" actions={sendNowAction}>
      {error ? <p className={styles.deliveryError}>{error}</p> : null}
      {deliveries === null || deliveries.length === 0 ? (
        <EmptyState
          title="No deliveries yet"
          description="This project's weekly summary has not been sent yet."
        />
      ) : (
        <ul className={styles.deliveryList}>
          {deliveries.map((delivery) => (
            <li key={delivery.id} className={styles.deliveryRow}>
              <div className={styles.deliveryMeta}>
                <span className={styles.deliveryRecipient}>
                  {delivery.recipientEmail ?? "No verified email yet"}
                </span>
                <p className={styles.deliveryDetail}>
                  Week of {formatWeekStart(delivery.weekStart)} · attempt
                  {delivery.attemptCount === 1 ? "" : "s"}{" "}
                  {delivery.attemptCount}
                </p>
                {delivery.lastError ? (
                  <p className={styles.deliveryError}>
                    {delivery.lastError}
                    {delivery.lastOutcomeAmbiguous
                      ? " (outcome unknown -- may have still sent)"
                      : ""}
                  </p>
                ) : null}
              </div>
              <div className={styles.deliveryActions}>
                <StatusBadge tone={STATUS_TONE[delivery.status]}>
                  {STATUS_LABEL[delivery.status]}
                </StatusBadge>
                {replayable(delivery.status) ? (
                  <Button
                    variant="secondary"
                    size="small"
                    onClick={() => replay(delivery.id)}
                    loading={pendingAction === delivery.id}
                    disabled={pendingAction !== null}
                  >
                    Replay
                  </Button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
