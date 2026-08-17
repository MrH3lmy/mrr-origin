import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CustomerDetail, CustomerTimeline } from "@/lib/api/types";

import { CustomerOverviewPanel } from "./CustomerOverviewPanel";

const { getCustomerTimeline, repairCustomerLink } = vi.hoisted(() => ({
  getCustomerTimeline: vi.fn(),
  repairCustomerLink: vi.fn(),
}));

vi.mock("@/lib/api/customers", () => ({
  getCustomerTimeline,
  repairCustomerLink,
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

function detail(overrides: Partial<CustomerDetail> = {}): CustomerDetail {
  return {
    stripeCustomerId: "cus_1",
    deleted: false,
    providerCreatedAt: "2026-01-01T00:00:00Z",
    subscriptions: [
      {
        stripeSubscriptionId: "sub_1",
        status: "active",
        currency: "USD",
        currentPeriodStart: "2026-01-01T00:00:00Z",
        currentPeriodEnd: "2026-02-01T00:00:00Z",
        cancelAtPeriodEnd: false,
        cancelAt: null,
        canceledAt: null,
        trialStart: null,
        trialEnd: null,
      },
    ],
    currentMrr: [{ currency: "USD", amountMinor: 1500 }],
    acquisition: {
      movementId: "mv-1",
      effectiveAt: "2026-01-05T00:00:00Z",
      modelVersion: "attribution-v1",
      status: "STRONG",
      unattributedReason: null,
      firstTouch: {
        touchpointId: "tp-1",
        occurredAt: "2025-12-01T00:00:00Z",
        source: "google",
        campaign: "spring",
        landingPage: "https://example.test/",
      },
      lastTouch: {
        touchpointId: "tp-2",
        occurredAt: "2025-12-15T00:00:00Z",
        source: "newsletter",
        campaign: "launch",
        landingPage: "https://example.test/launch",
      },
      customerLinkEvidenceId: "link-1",
      sourceReferences: ["stripe_customer_links:link-1"],
      calculatedAt: "2026-01-05T01:00:00Z",
    },
    activeLink: {
      id: "link-1",
      externalUserId: "user_1",
      evidenceSource: "EXPLICIT_API",
      linkedBySubjectId: "owner",
      createdAt: "2026-01-01T00:00:00Z",
    },
    repairCapability: { canRepair: true, reason: null },
    ...overrides,
  };
}

const baseProps = { workspaceId: "ws-1", projectId: "proj-1" };

describe("CustomerOverviewPanel", () => {
  beforeEach(() => {
    getCustomerTimeline.mockReset();
    repairCustomerLink.mockReset();
  });

  it("shows current MRR, acquisition status, and subscription status", () => {
    render(
      <CustomerOverviewPanel
        {...baseProps}
        detail={detail()}
        onRepaired={vi.fn()}
      />,
    );

    expect(screen.getByText("$15")).toBeInTheDocument();
    expect(screen.getAllByText("Attributed").length).toBeGreaterThan(0);
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  it("exposes the raw reason code alongside the humanized text in the why-attribution disclosure", () => {
    render(
      <CustomerOverviewPanel
        {...baseProps}
        detail={detail({
          acquisition: {
            ...detail().acquisition,
            status: "UNATTRIBUTED",
            unattributedReason: "NO_ELIGIBLE_TOUCHPOINT",
            firstTouch: null,
            lastTouch: null,
          },
        })}
        onRepaired={vi.fn()}
      />,
    );

    expect(
      screen.getByText(
        /The user is linked, but no eligible acquisition touchpoint exists/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("(NO_ELIGIBLE_TOUCHPOINT)")).toBeInTheDocument();
  });

  it("offers an authorized repair action to a manager", async () => {
    const user = userEvent.setup();
    const onRepaired = vi.fn();
    repairCustomerLink.mockResolvedValue({
      link: {
        id: "link-2",
        workspaceId: "ws-1",
        projectId: "proj-1",
        externalUserId: "user_2",
        stripeCustomerId: "cus_1",
        evidenceSource: "EXPLICIT_API",
        evidenceReference: "ref",
        linkedBySubjectId: "owner",
        createdAt: "2026-01-06T00:00:00Z",
      },
      actionType: "CORRECTED",
      previousIdentityStripeCustomerId: null,
      targetCustomerAttribution: [],
      displacedCustomerId: null,
      displacedCustomerAttribution: [],
    });
    const refreshedTimeline = { detail: detail() } as CustomerTimeline;
    getCustomerTimeline.mockResolvedValue(refreshedTimeline);

    render(
      <CustomerOverviewPanel
        {...baseProps}
        detail={detail()}
        onRepaired={onRepaired}
      />,
    );

    await user.type(
      screen.getByLabelText(/Correct the linked application user ID/),
      "user_2",
    );
    await user.click(screen.getByRole("button", { name: "Correct link" }));

    expect(await screen.findByText("Linked to user_2.")).toBeInTheDocument();
    expect(repairCustomerLink).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      "user_2",
      "cus_1",
    );
    expect(onRepaired).toHaveBeenCalledWith(refreshedTimeline);
  });

  it("shows an explanation without a usable mutation control to an unauthorized user", () => {
    render(
      <CustomerOverviewPanel
        {...baseProps}
        detail={detail({
          repairCapability: {
            canRepair: false,
            reason: "WORKSPACE_ROLE_INSUFFICIENT",
          },
        })}
        onRepaired={vi.fn()}
      />,
    );

    expect(
      screen.getByText("Repair requires manager permission."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Correct link|Link customer/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText(/application user ID/),
    ).not.toBeInTheDocument();
  });
});
