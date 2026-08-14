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

  const isReceiving = diagnostics.state === "RECEIVING";
  const isExpired = verification?.status === "EXPIRED" && !isReceiving;

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
    if (isReceiving || !verification || verification.status !== "PENDING")
      return;
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
  }, [verification?.status, isReceiving]);

  const copy = DIAGNOSTIC_STATE_COPY[diagnostics.state];

  return (
    <Panel
      title="Verify tracking"
      subtitle="Confirm events from your site are reaching MRROrigin."
      actions={<StatusBadge tone={copy.tone}>{copy.label}</StatusBadge>}
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
            detail="Live checks expire after 15 minutes. Start a new one after refreshing your site."
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
        ) : (
          <Alert tone={copy.tone} title={copy.headline} detail={copy.detail}>
            {!verification ? (
              <Button
                variant="primary"
                size="small"
                onClick={handleStart}
                loading={starting}
              >
                Start verification
              </Button>
            ) : (
              <Button
                variant="secondary"
                size="small"
                onClick={refresh}
                loading={checking}
              >
                Check again
              </Button>
            )}
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
