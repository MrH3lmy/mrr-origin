import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { RevenueOverview } from "@/lib/api/types";

import { RevenueSummary } from "./RevenueSummary";

const baseOverview: RevenueOverview = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  from: "2026-04-01T00:00:00Z",
  to: "2026-05-01T00:00:00Z",
  calculationVersion: "mrr-v1",
  modelVersion: "attribution-v1",
  movementTotals: [
    {
      currency: "USD",
      movementType: "NEW",
      totalMinor: 200000,
      movementCount: 4,
    },
    {
      currency: "USD",
      movementType: "CHURN",
      totalMinor: 30000,
      movementCount: 1,
    },
  ],
  currentMrr: [{ currency: "USD", totalMinor: 900000, customerCount: 12 }],
  sourceHighlights: [
    { source: "google", currency: "USD", totalMinor: 150000, customerCount: 3 },
    { source: null, currency: "USD", totalMinor: 50000, customerCount: 1 },
  ],
};

const baseProps = {
  selectedMovementType: null,
  selectedSource: null,
  onSelectMovementType: vi.fn(),
  onSelectSource: vi.fn(),
};

describe("RevenueSummary", () => {
  it("renders current MRR, new MRR, and churned MRR for the period", () => {
    render(<RevenueSummary overview={baseOverview} {...baseProps} />);

    expect(screen.getByText("$9,000")).toBeInTheDocument(); // current MRR
    expect(screen.getByText("12 paying customers")).toBeInTheDocument();
    expect(screen.getByText("$2,000 (4)")).toBeInTheDocument(); // new MRR row
    expect(screen.getByText("$300 (1)")).toBeInTheDocument(); // churn row
  });

  it("always shows retained MRR as not available rather than a fabricated number", () => {
    render(<RevenueSummary overview={baseOverview} {...baseProps} />);

    expect(screen.getByText("Retained MRR")).toBeInTheDocument();
    expect(screen.getByText(/not available yet/i)).toBeInTheDocument();
  });

  it("shows source highlights ranked by New MRR, with unattributed as a neutral bucket", () => {
    render(<RevenueSummary overview={baseOverview} {...baseProps} />);

    expect(screen.getByText("google")).toBeInTheDocument();
    expect(screen.getAllByText("Unattributed").length).toBeGreaterThan(0);
  });

  it("invokes onSelectMovementType when a movement row is clicked", async () => {
    const user = userEvent.setup();
    const onSelectMovementType = vi.fn();
    render(
      <RevenueSummary
        overview={baseOverview}
        {...baseProps}
        onSelectMovementType={onSelectMovementType}
      />,
    );

    await user.click(screen.getByRole("button", { name: /new mrr/i }));

    expect(onSelectMovementType).toHaveBeenCalledWith("NEW");
  });

  it("disables movement rows with zero amount so they cannot be selected as a drill-down filter", () => {
    render(<RevenueSummary overview={baseOverview} {...baseProps} />);

    expect(
      screen.getByRole("button", { name: /expansion mrr/i }),
    ).toBeDisabled();
  });

  it("renders an honest empty state when there is no movement data at all", () => {
    render(
      <RevenueSummary
        overview={{
          ...baseOverview,
          movementTotals: [],
          currentMrr: [],
          sourceHighlights: [],
        }}
        {...baseProps}
      />,
    );

    expect(
      screen.getByText(/no mrr movement in this period yet/i),
    ).toBeInTheDocument();
  });
});
