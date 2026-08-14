import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type { StripeBillingHealthReport } from "@/lib/api/types";

import { StripeSection } from "./StripeSection";

const { getStripeHealth, startStripeOauth, resumeBackfill, disconnectStripe } =
  vi.hoisted(() => ({
    getStripeHealth: vi.fn(),
    startStripeOauth: vi.fn(),
    resumeBackfill: vi.fn(),
    disconnectStripe: vi.fn(),
  }));

vi.mock("@/lib/api/stripe", () => ({
  getStripeHealth,
  startStripeOauth,
  resumeBackfill,
  disconnectStripe,
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

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

describe("StripeSection", () => {
  beforeEach(() => {
    getStripeHealth.mockReset();
    startStripeOauth.mockReset();
    resumeBackfill.mockReset();
    disconnectStripe.mockReset();
    vi.stubGlobal(
      "open",
      vi.fn(() => ({ location: { href: "" }, close: vi.fn() })),
    );
  });

  it("shows 'not connected' and offers the connect action", () => {
    render(
      <StripeSection
        workspaceId="ws-1"
        health={health()}
        onHealthChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Not connected")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /connect with stripe/i }),
    ).toBeInTheDocument();
  });

  it("shows connected-but-syncing as distinct from healthy", () => {
    render(
      <StripeSection
        workspaceId="ws-1"
        health={health({
          status: "STALE",
          reasons: ["BACKFILL_IN_PROGRESS"],
          connectionPresent: true,
          connectionStatus: "ACTIVE",
          verificationStatus: "VERIFIED",
          connectionMode: "TEST",
          backfillPhase: "SUBSCRIPTIONS",
          backfillComplete: false,
        })}
        onHealthChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Connected")).toBeInTheDocument();
    expect(screen.getByText("Stale")).toBeInTheDocument();
    expect(screen.getByText(/not fully current/i)).toBeInTheDocument();
    // "Connected" must never visually imply healthy while a sync is degraded/incomplete.
    expect(screen.queryByText("Healthy")).not.toBeInTheDocument();
  });

  it("shows a fully healthy project", () => {
    render(
      <StripeSection
        workspaceId="ws-1"
        health={health({
          status: "HEALTHY",
          reasons: [],
          connectionPresent: true,
          connectionStatus: "ACTIVE",
          verificationStatus: "VERIFIED",
          connectionMode: "LIVE",
          backfillPhase: "DONE",
          backfillComplete: true,
        })}
        onHealthChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Healthy")).toBeInTheDocument();
    expect(screen.getByText(/healthy and up to date/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /resume sync/i }),
    ).not.toBeInTheDocument();
  });

  it("shows degraded with humanized recovery reasons, not just a status code", () => {
    render(
      <StripeSection
        workspaceId="ws-1"
        health={health({
          status: "DEGRADED",
          reasons: ["WEBHOOK_FAILURES_PRESENT", "CONNECTION_UNVERIFIED"],
          connectionPresent: true,
          connectionStatus: "ACTIVE",
          verificationStatus: "FAILED",
        })}
        onHealthChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Degraded")).toBeInTheDocument();
    expect(screen.getByText(/could not verify access/i)).toBeInTheDocument();
    expect(screen.getByText(/webhook failed processing/i)).toBeInTheDocument();
  });

  it("opens a window synchronously (before the network call) so the popup isn't blocked", async () => {
    const user = userEvent.setup();
    let resolveOauth: (value: { authorizationUrl: string }) => void = () => {};
    startStripeOauth.mockReturnValue(
      new Promise((resolve) => (resolveOauth = resolve)),
    );

    render(
      <StripeSection
        workspaceId="ws-1"
        health={health()}
        onHealthChange={vi.fn()}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: /connect with stripe/i }),
    );

    // window.open must have been called already, before the async oauth-start call resolves.
    expect(window.open).toHaveBeenCalledWith("", "_blank");
    resolveOauth({ authorizationUrl: "https://stripe.example/connect" });
    await waitFor(() => expect(startStripeOauth).toHaveBeenCalled());
  });

  it("shows a message when the browser blocks the popup", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "open",
      vi.fn(() => null),
    );

    render(
      <StripeSection
        workspaceId="ws-1"
        health={health()}
        onHealthChange={vi.fn()}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: /connect with stripe/i }),
    );

    expect(
      await screen.findByText(/blocked the stripe window/i),
    ).toBeInTheDocument();
    expect(startStripeOauth).not.toHaveBeenCalled();
  });

  it("resumes backfill and reports the refreshed health to the parent", async () => {
    const user = userEvent.setup();
    const onHealthChange = vi.fn();
    resumeBackfill.mockResolvedValue({
      pagesProcessed: 1,
      phase: "INVOICES",
      complete: false,
      connectionEligible: true,
    });
    getStripeHealth.mockResolvedValue(
      health({ backfillPhase: "INVOICES", connectionPresent: true }),
    );

    render(
      <StripeSection
        workspaceId="ws-1"
        health={health({
          connectionPresent: true,
          backfillPhase: "SUBSCRIPTIONS",
        })}
        onHealthChange={onHealthChange}
      />,
    );

    await user.click(screen.getByRole("button", { name: /resume sync/i }));

    await waitFor(() =>
      expect(resumeBackfill).toHaveBeenCalledWith({}, "ws-1"),
    );
    await waitFor(() =>
      expect(onHealthChange).toHaveBeenCalledWith(
        health({ backfillPhase: "INVOICES", connectionPresent: true }),
      ),
    );
  });

  it("shows a retry-safe error when disconnect fails", async () => {
    const user = userEvent.setup();
    disconnectStripe.mockRejectedValue(
      new ApiError(502, "stripe_unavailable", "Stripe request failed"),
    );

    render(
      <StripeSection
        workspaceId="ws-1"
        health={health({
          status: "HEALTHY",
          reasons: [],
          connectionPresent: true,
          connectionStatus: "ACTIVE",
          verificationStatus: "VERIFIED",
          backfillComplete: true,
        })}
        onHealthChange={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: /disconnect/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Stripe request failed",
    );
  });
});
