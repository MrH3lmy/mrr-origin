import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type {
  ActiveIngestionKey,
  AllowedDomain,
  Project,
  ProjectDiagnosticsReport,
  StripeBillingHealthReport,
  VerificationAttempt,
} from "@/lib/api/types";

import { ProjectStatusView } from "./ProjectStatusView";

vi.mock("@/lib/api/tracking", () => ({
  getActiveIngestionKey: vi.fn(),
  issueOrRotateIngestionKey: vi.fn(),
  listAllowedDomains: vi.fn(),
  addAllowedDomain: vi.fn(),
  removeAllowedDomain: vi.fn(),
  getDiagnostics: vi.fn(),
  getVerificationStatus: vi.fn(),
  startVerification: vi.fn(),
}));
vi.mock("@/lib/api/stripe", () => ({
  getStripeHealth: vi.fn(),
  startStripeOauth: vi.fn(),
  resumeBackfill: vi.fn(),
  disconnectStripe: vi.fn(),
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const project: Project = {
  id: "proj-1",
  workspaceId: "ws-1",
  name: "Production",
  domain: "app.example.com",
  publicKey: "pk_x",
  timezone: "UTC",
  createdAt: "2026-08-14T00:00:00Z",
};

const noKey: ActiveIngestionKey = {
  present: false,
  id: null,
  prefix: null,
  createdAt: null,
};
const activeKey: ActiveIngestionKey = {
  present: true,
  id: "k1",
  prefix: "mrr_abc",
  createdAt: "2026-08-14T00:00:00Z",
};
const domains: AllowedDomain[] = [];

function diagnostics(
  state: ProjectDiagnosticsReport["state"],
): ProjectDiagnosticsReport {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    state,
    everReceivedTraffic: state === "RECEIVING",
    lastAcceptedEventAt: null,
    lastAcceptedEventType: null,
    blockedOrigin: { count: 0, lastOccurredAt: null },
    invalidKey: { count: 0, lastOccurredAt: null },
    invalidPayload: { count: 0, lastOccurredAt: null },
    identityCoverage: { totalVisitors: 0, identifiedVisitors: 0 },
    computedAt: "2026-08-14T00:00:00Z",
  };
}

function health(
  overrides: Partial<StripeBillingHealthReport> = {},
): StripeBillingHealthReport {
  return {
    workspaceId: "ws-1",
    status: "DEGRADED",
    reasons: ["NO_ACTIVE_CONNECTION"],
    connectionPresent: false,
    connectionStatus: null,
    verificationStatus: null,
    connectionMode: null,
    backfillPhase: null,
    backfillComplete: false,
    lastSyncAt: null,
    syncLagSeconds: null,
    oldestPendingEventAgeSeconds: null,
    pendingWebhookEvents: 0,
    orphanedWebhookEvents: 0,
    processedWebhookEvents: 0,
    failedWebhookEventsTransient: 0,
    failedWebhookEventsUnsupported: 0,
    failedWebhookEventsLegacy: 0,
    ledgerTotals: {
      customers: 0,
      prices: 0,
      subscriptions: 0,
      invoices: 0,
      payments: 0,
      refunds: 0,
      discounts: 0,
    },
    reconciliationMismatches: [],
    computedAt: "2026-08-14T00:00:00Z",
    ...overrides,
  };
}

const verification: VerificationAttempt | null = null;

function renderView(overrides: {
  activeKey?: ActiveIngestionKey;
  diagnosticsState?: ProjectDiagnosticsReport["state"];
  healthOverrides?: Partial<StripeBillingHealthReport>;
}) {
  render(
    <ProjectStatusView
      workspaceId="ws-1"
      projectId="proj-1"
      project={project}
      initialActiveKey={overrides.activeKey ?? noKey}
      initialDomains={domains}
      initialDiagnostics={diagnostics(
        overrides.diagnosticsState ?? "NO_TRAFFIC",
      )}
      initialVerification={verification}
      initialHealth={health(overrides.healthOverrides)}
    />,
  );
}

describe("ProjectStatusView onboarding progression", () => {
  it("starts at 'Install tracker' with only 'Create project' complete", () => {
    renderView({});

    const steps = screen.getAllByRole("listitem");
    expect(steps[0]).toHaveTextContent("Create project");
    expect(steps[1]).toHaveTextContent("Install tracker");
    expect(steps[1]).toHaveAttribute("aria-current", "step");
    expect(screen.queryByText(/tracker healthy/i)).not.toBeInTheDocument();
  });

  it("advances to 'Verify tracking' once a key exists", () => {
    renderView({ activeKey: activeKey });

    const steps = screen.getAllByRole("listitem");
    expect(steps[2]).toHaveTextContent("Verify tracking");
    expect(steps[2]).toHaveAttribute("aria-current", "step");
  });

  it("advances to 'Connect Stripe' once the tracker is receiving events", () => {
    renderView({ activeKey, diagnosticsState: "RECEIVING" });

    const steps = screen.getAllByRole("listitem");
    expect(steps[3]).toHaveTextContent("Connect Stripe");
    expect(steps[3]).toHaveAttribute("aria-current", "step");
  });

  it("advances to 'Initial sync' once Stripe is connected but still syncing", () => {
    renderView({
      activeKey,
      diagnosticsState: "RECEIVING",
      healthOverrides: {
        status: "STALE",
        reasons: ["BACKFILL_IN_PROGRESS"],
        connectionPresent: true,
        connectionStatus: "ACTIVE",
        verificationStatus: "VERIFIED",
        backfillComplete: false,
      },
    });

    const steps = screen.getAllByRole("listitem");
    expect(steps[4]).toHaveTextContent("Initial sync");
    expect(steps[4]).toHaveAttribute("aria-current", "step");
    expect(screen.queryByText(/tracker healthy/i)).not.toBeInTheDocument();
  });

  it("shows the ready banner only once tracker + Stripe are both fully healthy", () => {
    renderView({
      activeKey,
      diagnosticsState: "RECEIVING",
      healthOverrides: {
        status: "HEALTHY",
        reasons: [],
        connectionPresent: true,
        connectionStatus: "ACTIVE",
        verificationStatus: "VERIFIED",
        backfillComplete: true,
      },
    });

    expect(
      screen.getByText(/tracker healthy · stripe healthy · project ready/i),
    ).toBeInTheDocument();
    // Every step, including "Ready" itself, reads as complete -- there is no longer a "current"
    // step to announce once the whole journey is finished.
    const steps = screen.getAllByRole("listitem");
    expect(steps[5]).toHaveTextContent("Ready");
    steps.forEach((step) => expect(step).not.toHaveAttribute("aria-current"));
  });

  it("does not show ready when Stripe is connected but degraded, even with a healthy tracker", () => {
    renderView({
      activeKey,
      diagnosticsState: "RECEIVING",
      healthOverrides: {
        status: "DEGRADED",
        reasons: ["WEBHOOK_FAILURES_PRESENT"],
        connectionPresent: true,
        connectionStatus: "ACTIVE",
        verificationStatus: "VERIFIED",
        backfillComplete: true,
      },
    });

    expect(
      screen.queryByText(/tracker healthy · stripe healthy · project ready/i),
    ).not.toBeInTheDocument();
  });
});
