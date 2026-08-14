"use client";

import { useEffect, useRef, useState } from "react";

import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import {
  disconnectStripe,
  getStripeHealth,
  resumeBackfill,
  startStripeOauth,
} from "@/lib/api/stripe";
import type {
  StripeBillingHealthReport,
  StripeConnectionMode,
} from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import {
  STRIPE_HEALTH_REASON_COPY,
  STRIPE_HEALTH_STATUS_COPY,
} from "@/lib/status-copy";

import styles from "./StripeSection.module.css";

const PHASES = [
  "CUSTOMERS",
  "PRICES",
  "SUBSCRIPTIONS",
  "INVOICES",
  "CHARGES",
  "REFUNDS",
  "DONE",
];
const CONNECT_POLL_INTERVAL_MS = 4000;
const MAX_CONNECT_POLLS = 45;

interface StripeSectionProps {
  workspaceId: string;
  health: StripeBillingHealthReport;
  onHealthChange: (health: StripeBillingHealthReport) => void;
}

export function StripeSection({
  workspaceId,
  health,
  onHealthChange: setHealth,
}: StripeSectionProps) {
  const [mode, setMode] = useState<StripeConnectionMode>("TEST");
  const [connecting, setConnecting] = useState(false);
  const [polling, setPolling] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollCount = useRef(0);

  async function refreshHealth() {
    const client = createBrowserClient();
    setHealth(await getStripeHealth(client, workspaceId));
  }

  function handleConnect() {
    setError(null);
    // Open the window synchronously, still inside the click's user-activation window --
    // opening it after the `await startStripeOauth` network call below would lose that
    // activation in strict browsers and get silently blocked.
    const popup = window.open("", "_blank");
    if (!popup) {
      setError(
        "Your browser blocked the Stripe window. Allow pop-ups and try again.",
      );
      return;
    }
    setConnecting(true);
    startStripeOauth(createBrowserClient(), workspaceId, mode)
      .then(({ authorizationUrl }) => {
        popup.location.href = authorizationUrl;
        pollCount.current = 0;
        setPolling(true);
      })
      .catch((connectError) => {
        popup.close();
        setError(
          connectError instanceof ApiError
            ? connectError.message
            : "Could not start the Stripe connection. Try again.",
        );
      })
      .finally(() => setConnecting(false));
  }

  useEffect(() => {
    if (!polling) return;
    const interval = window.setInterval(async () => {
      if (pollCount.current >= MAX_CONNECT_POLLS) {
        window.clearInterval(interval);
        setPolling(false);
        return;
      }
      pollCount.current += 1;
      try {
        const client = createBrowserClient();
        const next = await getStripeHealth(client, workspaceId);
        setHealth(next);
        if (next.connectionStatus === "ACTIVE") {
          window.clearInterval(interval);
          setPolling(false);
        }
      } catch {
        // transient failures during polling are silently retried on the next tick
      }
    }, CONNECT_POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [polling, workspaceId, setHealth]);

  async function handleResumeBackfill() {
    setResuming(true);
    setError(null);
    try {
      await resumeBackfill(createBrowserClient(), workspaceId);
      await refreshHealth();
    } catch (resumeError) {
      setError(
        resumeError instanceof ApiError
          ? resumeError.message
          : "Could not resume the sync. Try again.",
      );
    } finally {
      setResuming(false);
    }
  }

  async function handleDisconnect() {
    setDisconnecting(true);
    setError(null);
    try {
      await disconnectStripe(createBrowserClient(), workspaceId);
      await refreshHealth();
    } catch (disconnectError) {
      setError(
        disconnectError instanceof ApiError
          ? disconnectError.message
          : "Could not disconnect Stripe. Try again.",
      );
    } finally {
      setDisconnecting(false);
    }
  }

  const connected =
    health.connectionStatus === "ACTIVE" &&
    health.verificationStatus === "VERIFIED";
  const phaseIndex = health.backfillPhase
    ? PHASES.indexOf(health.backfillPhase)
    : -1;
  const progressPercent =
    phaseIndex >= 0 ? Math.round((phaseIndex / (PHASES.length - 1)) * 100) : 0;
  const statusCopy = STRIPE_HEALTH_STATUS_COPY[health.status];

  return (
    <div style={{ display: "grid", gap: 20 }}>
      <Panel
        title="Connect Stripe"
        subtitle="Stripe Connect OAuth, read-only access to your account."
        actions={
          <StatusBadge tone={connected ? "positive" : "neutral"}>
            {connected
              ? "Connected"
              : health.connectionPresent
                ? "Not active"
                : "Not connected"}
          </StatusBadge>
        }
      >
        <div style={{ display: "grid", gap: 14 }}>
          {!connected ? (
            <>
              <div
                className={styles.modeToggle}
                role="radiogroup"
                aria-label="Stripe environment"
              >
                {(["TEST", "LIVE"] as const).map((option) => (
                  <button
                    key={option}
                    type="button"
                    role="radio"
                    aria-checked={mode === option}
                    className={`${styles.modeOption} ${mode === option ? styles.modeOptionActive : ""}`}
                    onClick={() => setMode(option)}
                  >
                    {option === "TEST" ? "Test mode" : "Live mode"}
                  </button>
                ))}
              </div>
              <div>
                <Button
                  variant="primary"
                  onClick={handleConnect}
                  loading={connecting}
                >
                  Connect with Stripe
                </Button>
                {polling ? (
                  <p
                    style={{
                      margin: "8px 0 0",
                      fontSize: "0.8125rem",
                      color: "var(--ds-text-muted)",
                    }}
                    role="status"
                  >
                    Waiting for you to finish in the Stripe window that opened…
                  </p>
                ) : null}
              </div>
            </>
          ) : (
            <div
              style={{ fontSize: "0.8125rem", color: "var(--ds-text-muted)" }}
            >
              Connected in {health.connectionMode === "LIVE" ? "live" : "test"}{" "}
              mode.
              <div style={{ marginTop: 10 }}>
                <Button
                  variant="secondary"
                  size="small"
                  onClick={handleDisconnect}
                  loading={disconnecting}
                >
                  Disconnect
                </Button>
              </div>
            </div>
          )}
        </div>
      </Panel>

      <Panel
        title="Stripe data health"
        subtitle="Connection and sync are tracked separately — connected doesn't always mean healthy."
        actions={
          <StatusBadge tone={statusCopy.tone}>{statusCopy.label}</StatusBadge>
        }
      >
        <div style={{ display: "grid", gap: 16 }}>
          <Alert
            tone={statusCopy.tone}
            title={statusCopy.headline}
            detail={statusCopy.detail}
          />

          <div>
            <p
              style={{
                margin: "0 0 6px",
                fontSize: "0.8125rem",
                fontWeight: 600,
              }}
            >
              Initial sync
              {health.backfillComplete
                ? " — complete"
                : health.backfillPhase
                  ? ` — ${health.backfillPhase.toLowerCase()}`
                  : ""}
            </p>
            <div
              className={styles.progressTrack}
              role="progressbar"
              aria-valuenow={health.backfillComplete ? 100 : progressPercent}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="Initial Stripe sync progress"
            >
              <div
                className={styles.progressFill}
                style={{
                  width: `${health.backfillComplete ? 100 : progressPercent}%`,
                }}
              />
            </div>
            {!health.backfillComplete && health.connectionPresent ? (
              <Button
                variant="secondary"
                size="small"
                onClick={handleResumeBackfill}
                loading={resuming}
                style={{ marginTop: 10 }}
              >
                Resume sync
              </Button>
            ) : null}
          </div>

          <div className={styles.metricGrid}>
            <div>
              <p className={styles.metricLabel}>Customers</p>
              <p className={styles.metricValue}>
                {health.ledgerTotals.customers}
              </p>
            </div>
            <div>
              <p className={styles.metricLabel}>Subscriptions</p>
              <p className={styles.metricValue}>
                {health.ledgerTotals.subscriptions}
              </p>
            </div>
            <div>
              <p className={styles.metricLabel}>Invoices</p>
              <p className={styles.metricValue}>
                {health.ledgerTotals.invoices}
              </p>
            </div>
            <div>
              <p className={styles.metricLabel}>Last sync</p>
              <p
                className={styles.metricValue}
                style={{ fontSize: "0.8125rem" }}
              >
                {formatDateTime(health.lastSyncAt)}
              </p>
            </div>
          </div>

          {health.reasons.length > 0 ? (
            <div>
              <p
                style={{
                  margin: "0 0 6px",
                  fontSize: "0.8125rem",
                  fontWeight: 600,
                }}
              >
                What needs attention
              </p>
              <ul className={styles.reasonList}>
                {health.reasons.map((reason) => (
                  <li key={reason} className={styles.reasonItem}>
                    {STRIPE_HEALTH_REASON_COPY[reason]}
                    <span
                      style={{
                        display: "block",
                        marginTop: 2,
                        color: "var(--ds-text-muted)",
                        fontFamily: "ui-monospace, monospace",
                        fontSize: "0.6875rem",
                      }}
                    >
                      {reason}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {health.failedWebhookEventsTransient +
            health.failedWebhookEventsUnsupported +
            health.failedWebhookEventsLegacy >
          0 ? (
            <p
              style={{
                margin: 0,
                fontSize: "0.8125rem",
                color: "var(--ds-warning)",
              }}
            >
              {health.failedWebhookEventsTransient +
                health.failedWebhookEventsUnsupported +
                health.failedWebhookEventsLegacy}{" "}
              webhook event(s) failed processing and need review in a future
              data-health release.
            </p>
          ) : null}
        </div>
      </Panel>

      {error ? (
        <p
          role="alert"
          style={{
            margin: 0,
            color: "var(--ds-danger)",
            fontSize: "0.8125rem",
          }}
        >
          {error}
        </p>
      ) : null}
    </div>
  );
}
