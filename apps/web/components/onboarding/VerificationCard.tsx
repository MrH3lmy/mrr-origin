"use client";

import { useEffect, useRef, useState } from "react";

import { Alert } from "@/components/ui/Alert";
import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createBrowserClient } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import {
  getDiagnostics,
  getVerificationStatus,
  startVerification,
} from "@/lib/api/tracking";
import type {
  ProjectDiagnosticsReport,
  VerificationAttempt,
} from "@/lib/api/types";
import { DIAGNOSTIC_STATE_COPY } from "@/lib/status-copy";

const MAX_AUTO_POLLS = 20;
const POLL_INTERVAL_MS = 5000;

interface VerificationCardProps {
  workspaceId: string;
  projectId: string;
  diagnostics: ProjectDiagnosticsReport;
  onDiagnosticsChange: (diagnostics: ProjectDiagnosticsReport) => void;
  verification: VerificationAttempt | null;
  onVerificationChange: (verification: VerificationAttempt | null) => void;
  hasActiveKey: boolean;
}

export function VerificationCard({
  workspaceId,
  projectId,
  diagnostics,
  onDiagnosticsChange: setDiagnostics,
  verification,
  onVerificationChange: setVerification,
  hasActiveKey,
}: VerificationCardProps) {
  const [starting, setStarting] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollCount = useRef(0);

  const isVerified = verification?.status === "SUCCEEDED";
  const isExpired = verification?.status === "EXPIRED" && !isVerified;
  // Ordinary traffic (e.g. page views) can make diagnostics.state RECEIVING without the specific
  // verification-ping challenge ever having arrived -- that would otherwise let a founder progress
  // past this step without proving their key/origin/payload actually work end to end.
  const isPendingWhileOtherTrafficArrives =
    verification?.status === "PENDING" && diagnostics.state === "RECEIVING";

  async function refresh() {
    setChecking(true);
    setError(null);
    try {
      const client = createBrowserClient();
      const [nextDiagnostics, nextVerification] = await Promise.all([
        getDiagnostics(client, workspaceId, projectId),
        getVerificationStatus(client, workspaceId, projectId).catch(
          (verificationError) => {
            if (
              verificationError instanceof ApiError &&
              verificationError.status === 404
            )
              return null;
            throw verificationError;
          },
        ),
      ]);
      setDiagnostics(nextDiagnostics);
      setVerification(nextVerification);
    } catch (refreshError) {
      setError(
        refreshError instanceof ApiError
          ? refreshError.message
          : "Could not check status. Try again.",
      );
    } finally {
      setChecking(false);
    }
  }

  async function handleStart() {
    setStarting(true);
    setError(null);
    try {
      const client = createBrowserClient();
      const attempt = await startVerification(client, workspaceId, projectId);
      setVerification(attempt);
      pollCount.current = 0;
      await refresh();
    } catch (startError) {
      setError(
        startError instanceof ApiError
          ? startError.message
          : "Could not start verification. Try again.",
      );
    } finally {
      setStarting(false);
    }
  }

  useEffect(() => {
    if (!verification || verification.status !== "PENDING") return;
    const interval = window.setInterval(() => {
      if (pollCount.current >= MAX_AUTO_POLLS) {
        window.clearInterval(interval);
        return;
      }
      pollCount.current += 1;
      refresh();
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refresh is stable enough for polling cadence
  }, [verification?.status]);

  const diagnosticCopy = DIAGNOSTIC_STATE_COPY[diagnostics.state];
  const display = isVerified
    ? {
        tone: "positive" as const,
        label: "Verified",
        headline: "Tracker verified. Events are arriving.",
        detail:
          "Your installed tracker sent the verification event successfully. You can continue to Stripe.",
      }
    : isPendingWhileOtherTrafficArrives
      ? {
          tone: "warning" as const,
          label: "Awaiting verification event",
          headline: "Traffic is arriving, but the verification event hasn't.",
          detail:
            "Make sure you deployed the installation snippet above — it now includes a one-time verification line — then open your site and check again.",
        }
      : diagnosticCopy;

  return (
    <Panel
      title="Verify tracking"
      subtitle="Confirm events from your site are reaching MRROrigin."
      actions={<StatusBadge tone={display.tone}>{display.label}</StatusBadge>}
    >
      <div style={{ display: "grid", gap: 16 }}>
        {!hasActiveKey ? (
          <Alert
            tone="neutral"
            title="Generate an installation key first"
            detail="Verification needs a real key installed on your site before it can receive anything."
          />
        ) : isExpired ? (
          <Alert
            tone="warning"
            title="Verification check expired"
            detail="Live checks expire after 15 minutes. Start a new one, redeploy the updated snippet above, then open your site."
          >
            <Button
              variant="primary"
              size="small"
              onClick={handleStart}
              loading={starting}
            >
              Start a new check
            </Button>
          </Alert>
        ) : !verification ? (
          <Alert
            tone="neutral"
            title="Start verification to confirm your install end to end"
            detail="This adds a one-time check to the installation snippet above. Deploy it, open your site, then check again."
          >
            <Button
              variant="primary"
              size="small"
              onClick={handleStart}
              loading={starting}
            >
              Start verification
            </Button>
          </Alert>
        ) : isVerified ? (
          <Alert
            tone={display.tone}
            title={display.headline}
            detail={display.detail}
          />
        ) : (
          <Alert
            tone={display.tone}
            title={display.headline}
            detail={display.detail}
          >
            <Button
              variant="secondary"
              size="small"
              onClick={refresh}
              loading={checking}
            >
              Check again
            </Button>
          </Alert>
        )}

        <dl
          style={{
            margin: 0,
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))",
            gap: 12,
            fontSize: "0.8125rem",
          }}
        >
          <div>
            <dt style={{ color: "var(--ds-text-muted)" }}>Reason code</dt>
            <dd style={{ margin: 0, fontFamily: "ui-monospace, monospace" }}>
              {diagnostics.state}
            </dd>
          </div>
          <div>
            <dt style={{ color: "var(--ds-text-muted)" }}>
              Verification status
            </dt>
            <dd style={{ margin: 0, fontFamily: "ui-monospace, monospace" }}>
              {verification?.status ?? "NOT_STARTED"}
            </dd>
          </div>
          <div>
            <dt style={{ color: "var(--ds-text-muted)" }}>
              Blocked-origin attempts
            </dt>
            <dd style={{ margin: 0 }}>{diagnostics.blockedOrigin.count}</dd>
          </div>
          <div>
            <dt style={{ color: "var(--ds-text-muted)" }}>
              Invalid-key attempts
            </dt>
            <dd style={{ margin: 0 }}>{diagnostics.invalidKey.count}</dd>
          </div>
          <div>
            <dt style={{ color: "var(--ds-text-muted)" }}>
              Invalid-payload attempts
            </dt>
            <dd style={{ margin: 0 }}>{diagnostics.invalidPayload.count}</dd>
          </div>
        </dl>

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
    </Panel>
  );
}
