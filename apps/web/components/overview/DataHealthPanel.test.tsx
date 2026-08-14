import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type {
  AttributionCoverage,
  ProjectDiagnosticsReport,
  StripeBillingHealthReport,
} from "@/lib/api/types";

import { DataHealthPanel } from "./DataHealthPanel";

const healthyStripe: StripeBillingHealthReport = {
  workspaceId: "ws-1",
  status: "HEALTHY",
  reasons: [],
  connectionPresent: true,
  connectionStatus: "ACTIVE",
  verificationStatus: "VERIFIED",
  connectionMode: "TEST",
  backfillPhase: "DONE",
  backfillComplete: true,
  lastSyncAt: "2026-04-01T00:00:00Z",
  syncLagSeconds: 0,
  oldestPendingEventAgeSeconds: null,
  pendingWebhookEvents: 0,
  orphanedWebhookEvents: 0,
  processedWebhookEvents: 10,
  failedWebhookEventsTransient: 0,
  failedWebhookEventsUnsupported: 0,
  failedWebhookEventsLegacy: 0,
  ledgerTotals: {
    customers: 5,
    prices: 2,
    subscriptions: 5,
    invoices: 10,
    payments: 10,
    refunds: 0,
    discounts: 0,
  },
  reconciliationMismatches: [],
  computedAt: "2026-04-01T00:00:00Z",
};

const receivingDiagnostics: ProjectDiagnosticsReport = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  state: "RECEIVING",
  everReceivedTraffic: true,
  lastAcceptedEventAt: "2026-04-01T00:00:00Z",
  lastAcceptedEventType: "pageview",
  blockedOrigin: { count: 0, lastOccurredAt: null },
  invalidKey: { count: 0, lastOccurredAt: null },
  invalidPayload: { count: 0, lastOccurredAt: null },
  identityCoverage: { totalVisitors: 10, identifiedVisitors: 5 },
  computedAt: "2026-04-01T00:00:00Z",
};

const fullCoverage: AttributionCoverage = {
  modelVersion: "attribution-v1",
  eligibleNewCustomers: 4,
  attributedNewCustomers: 4,
  coverageRatio: 1,
  exclusionReasonCounts: {},
};

const partialCoverage: AttributionCoverage = {
  modelVersion: "attribution-v1",
  eligibleNewCustomers: 4,
  attributedNewCustomers: 2,
  coverageRatio: 0.5,
  exclusionReasonCounts: { NO_ACTIVE_LINK: 1, NOT_RECALCULATED: 1 },
};

describe("DataHealthPanel", () => {
  it("shows an all-healthy summary when every signal is healthy and coverage is complete", () => {
    render(
      <DataHealthPanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={fullCoverage}
        stripeHealth={healthyStripe}
        diagnostics={receivingDiagnostics}
      />,
    );

    expect(screen.getByText("All systems healthy")).toBeInTheDocument();
    expect(screen.getByText("100%")).toBeInTheDocument();
  });

  it("humanizes exclusion reasons and flags attention when coverage is partial", () => {
    render(
      <DataHealthPanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={partialCoverage}
        stripeHealth={healthyStripe}
        diagnostics={receivingDiagnostics}
      />,
    );

    expect(screen.getByText("Needs attention")).toBeInTheDocument();
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(
      screen.getByText(
        "No application user is linked to this Stripe customer.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Attribution has not been recalculated for the current model yet.",
      ),
    ).toBeInTheDocument();
  });

  it("surfaces a degraded Stripe signal even when coverage is complete", () => {
    render(
      <DataHealthPanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={fullCoverage}
        stripeHealth={{
          ...healthyStripe,
          status: "DEGRADED",
          reasons: ["WEBHOOK_FAILURES_PRESENT"],
        }}
        diagnostics={receivingDiagnostics}
      />,
    );

    expect(screen.getByText("Needs attention")).toBeInTheDocument();
    expect(screen.getByText("Degraded")).toBeInTheDocument();
  });

  it("never labels unattributed coverage as a danger/failure state", () => {
    render(
      <DataHealthPanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={partialCoverage}
        stripeHealth={healthyStripe}
        diagnostics={receivingDiagnostics}
      />,
    );

    // The coverage badge itself must never render with danger styling -- gaps are attention-level,
    // not a failure, per DESIGN_SYSTEM.md.
    expect(screen.getByText("50%").className).not.toMatch(/danger/i);
  });
});
