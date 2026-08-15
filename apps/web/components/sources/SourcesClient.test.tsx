import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { MrrMovementEntry, SourceComparison } from "@/lib/api/types";

import { SourcesClient } from "./SourcesClient";

const { getSourceComparison, listMrrMovements } = vi.hoisted(() => ({
  getSourceComparison: vi.fn(),
  listMrrMovements: vi.fn(),
}));

vi.mock("@/lib/api/reporting", () => ({
  getSourceComparison,
  listMrrMovements,
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  from: "2026-04-01T00:00:00Z",
  to: "2026-05-01T00:00:00Z",
};

function sourceComparison(): SourceComparison {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    from: baseProps.from,
    to: baseProps.to,
    dimension: "SOURCE",
    source: null,
    campaign: null,
    rows: [
      {
        dimensionValue: "google",
        currency: "USD",
        movementType: "NEW",
        totalMinor: 1000,
        customerCount: 2,
      },
    ],
    unavailableMetrics: [
      { metric: "RETAINED_MRR", reason: "Not available yet — depends on #25." },
      { metric: "NRR", reason: "Not available yet — depends on #25." },
    ],
  };
}

function campaignComparison(): SourceComparison {
  return {
    ...sourceComparison(),
    dimension: "CAMPAIGN",
    source: "google",
    rows: [
      {
        dimensionValue: "spring_sale",
        currency: "USD",
        movementType: "NEW",
        totalMinor: 700,
        customerCount: 1,
      },
    ],
  };
}

const movementEntry: MrrMovementEntry = {
  movementId: "mv-1",
  stripeCustomerId: "cus_1",
  currency: "USD",
  amountMinor: 1000,
  movementType: "NEW",
  effectiveAt: "2026-04-05T00:00:00Z",
  confidence: "STRONG",
  unattributedReason: null,
  firstTouch: {
    source: "google",
    campaign: "spring_sale",
    landingPage: "https://example.test/a",
  },
  lastTouch: {
    source: "google",
    campaign: "spring_sale",
    landingPage: "https://example.test/a",
  },
};

describe("SourcesClient", () => {
  beforeEach(() => {
    getSourceComparison.mockReset();
    listMrrMovements.mockReset();
    listMrrMovements.mockResolvedValue({ entries: [], nextCursor: null });
  });

  it("starts at the source dimension with 'All sources' as the current breadcrumb", async () => {
    getSourceComparison.mockResolvedValue(sourceComparison());

    render(<SourcesClient {...baseProps} />);

    expect(await screen.findByText("google")).toBeInTheDocument();
    expect(screen.getByText("All sources")).toBeInTheDocument();
    expect(getSourceComparison).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      "SOURCE",
      { source: undefined, campaign: undefined },
    );
  });

  it("drills into campaigns within a source when its label is clicked, and back out via the breadcrumb", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValueOnce(sourceComparison());
    getSourceComparison.mockResolvedValueOnce(campaignComparison());

    render(<SourcesClient {...baseProps} />);

    const sourceLabel = await screen.findByRole("button", { name: "google" });
    await user.click(sourceLabel);

    expect(await screen.findByText("spring_sale")).toBeInTheDocument();
    expect(getSourceComparison).toHaveBeenLastCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      "CAMPAIGN",
      { source: "google", campaign: undefined },
    );
    // Breadcrumb now shows the drilled-into source as the current (non-clickable) crumb, plus a
    // clickable "All sources" crumb to go back to the top level.
    expect(
      screen.getByRole("button", { name: "All sources" }),
    ).toBeInTheDocument();

    getSourceComparison.mockResolvedValueOnce(sourceComparison());
    await user.click(screen.getByRole("button", { name: "All sources" }));
    expect(await screen.findByText("All sources")).toBeInTheDocument();
  });

  it("reconciles the evidence table exactly to the clicked New MRR cell", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue(sourceComparison());
    listMrrMovements.mockResolvedValue({
      entries: [movementEntry],
      nextCursor: null,
    });

    render(<SourcesClient {...baseProps} />);

    const amount = await screen.findByRole("button", { name: "$10" });
    await user.click(amount);

    expect(await screen.findByText("cus_1")).toBeInTheDocument();
    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({
        movementType: "NEW",
        source: "google",
        currency: "USD",
      }),
    );
  });

  it("filters evidence to the Unattributed bucket using the UNATTRIBUTED sentinel", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue({
      ...sourceComparison(),
      rows: [
        {
          dimensionValue: null,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 400,
          customerCount: 1,
        },
      ],
    });
    listMrrMovements.mockResolvedValue({ entries: [], nextCursor: null });

    render(<SourcesClient {...baseProps} />);

    const amount = await screen.findByRole("button", { name: "$4" });
    await user.click(amount);

    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({ source: "UNATTRIBUTED" }),
    );
  });
});
