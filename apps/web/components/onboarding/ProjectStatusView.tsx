"use client";

import { useState } from "react";

import { Alert } from "@/components/ui/Alert";
import { ButtonLink } from "@/components/ui/Button";
import type {
  ActiveIngestionKey,
  AllowedDomain,
  Project,
  ProjectDiagnosticsReport,
  StripeBillingHealthReport,
  VerificationAttempt,
} from "@/lib/api/types";

import { Stepper, type StepDefinition } from "./Stepper";
import { StripeSection } from "./StripeSection";
import { TrackerInstallCard } from "./TrackerInstallCard";
import { VerificationCard } from "./VerificationCard";

const STEPS: StepDefinition[] = [
  { key: "create-project", label: "Create project" },
  { key: "install-tracker", label: "Install tracker" },
  { key: "verify", label: "Verify tracking" },
  { key: "connect-stripe", label: "Connect Stripe" },
  { key: "initial-sync", label: "Initial sync" },
  { key: "ready", label: "Ready" },
];

interface ProjectStatusViewProps {
  workspaceId: string;
  projectId: string;
  project: Project;
  initialActiveKey: ActiveIngestionKey;
  initialDomains: AllowedDomain[];
  initialDiagnostics: ProjectDiagnosticsReport;
  initialVerification: VerificationAttempt | null;
  initialHealth: StripeBillingHealthReport;
}

/**
 * Owns the state shared across the tracker/verification/Stripe cards so the stepper and ready
 * banner above them update live as the founder completes each step, instead of only reflecting
 * the state as of the last full page load.
 */
export function ProjectStatusView({
  workspaceId,
  projectId,
  project,
  initialActiveKey,
  initialDomains,
  initialDiagnostics,
  initialVerification,
  initialHealth,
}: ProjectStatusViewProps) {
  const [activeKey, setActiveKey] = useState(initialActiveKey);
  const [diagnostics, setDiagnostics] = useState(initialDiagnostics);
  const [verification, setVerification] = useState(initialVerification);
  const [health, setHealth] = useState(initialHealth);

  const trackerHealthy = diagnostics.state === "RECEIVING";
  const stripeConnected =
    health.connectionStatus === "ACTIVE" &&
    health.verificationStatus === "VERIFIED";
  const stripeSyncComplete = health.backfillComplete;
  const stripeHealthy = health.status === "HEALTHY";
  const ready =
    trackerHealthy && stripeConnected && stripeSyncComplete && stripeHealthy;

  const completedKeys = new Set<string>(["create-project"]);
  if (activeKey.present) completedKeys.add("install-tracker");
  if (trackerHealthy) completedKeys.add("verify");
  if (stripeConnected) completedKeys.add("connect-stripe");
  if (stripeSyncComplete && stripeHealthy) completedKeys.add("initial-sync");
  if (ready) completedKeys.add("ready");

  const currentKey = !activeKey.present
    ? "install-tracker"
    : !trackerHealthy
      ? "verify"
      : !stripeConnected
        ? "connect-stripe"
        : !(stripeSyncComplete && stripeHealthy)
          ? "initial-sync"
          : "ready";

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <Stepper
        steps={STEPS}
        currentKey={currentKey}
        completedKeys={completedKeys}
      />

      {ready ? (
        <Alert
          tone="positive"
          title="Tracker healthy · Stripe healthy · Project ready"
        >
          <ButtonLink
            variant="primary"
            size="small"
            href={`/app/${workspaceId}/projects/${projectId}/overview`}
          >
            Continue
          </ButtonLink>
        </Alert>
      ) : null}

      <TrackerInstallCard
        workspaceId={workspaceId}
        projectId={projectId}
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={setActiveKey}
        initialDomains={initialDomains}
      />

      <VerificationCard
        workspaceId={workspaceId}
        projectId={projectId}
        diagnostics={diagnostics}
        onDiagnosticsChange={setDiagnostics}
        verification={verification}
        onVerificationChange={setVerification}
        hasActiveKey={activeKey.present}
      />

      <StripeSection
        workspaceId={workspaceId}
        health={health}
        onHealthChange={setHealth}
      />
    </div>
  );
}
