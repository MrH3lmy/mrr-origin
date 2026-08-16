import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type {
  ComparisonRow,
  RetentionSummaryRow,
  SourceComparison,
} from "@/lib/api/types";

import { ComparisonTable } from "./ComparisonTable";

const { getSourceComparison } = vi.hoisted(() => ({
  getSourceComparison: vi.fn(),
}));

vi.mock("@/lib/api/reporting", () => ({ getSourceComparison }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

function comparison(
  rows: ComparisonRow[],
  retention: RetentionSummaryRow[] = [],
): SourceComparison {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    from: "2026-04-01T00:00:00Z",
    to: "2026-05-01T00:00:00Z",
    dimension: "SOURCE",
    source: null,
    campaign: null,
    campaignMissing: false,
    rows,
    retentionAgeDays: 30,
    retention,
  };
}

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  from: "2026-04-01T00:00:00Z",
  to: "2026-05-01T00:00:00Z",
  dimension: "SOURCE" as const,
  source: null,
  campaign: null,
  campaignMissing: false,
  onDrillDown: vi.fn(),
  onSelectMetric: vi.fn(),
  selectedMetric: null,
};

describe("ComparisonTable", () => {
  beforeEach(() => {
    getSourceComparison.mockReset();
  });

  it("shows a loading state, then the comparison rows", async () => {
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 2,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);

    expect(screen.getByLabelText(/loading comparison/i)).toBeInTheDocument();
    expect(await screen.findByText("google")).toBeInTheDocument();
    expect(screen.getByText("$10")).toBeInTheDocument();
  });

  it("shows an empty state with no rows", async () => {
    getSourceComparison.mockResolvedValue(comparison([]));

    render(<ComparisonTable {...baseProps} />);

    expect(
      await screen.findByText(/nothing to compare in this period/i),
    ).toBeInTheDocument();
  });

  it("shows a retry-safe error message when loading fails", async () => {
    getSourceComparison.mockRejectedValue(
      new ApiError(500, "server_error", "Backend unavailable"),
    );

    render(<ComparisonTable {...baseProps} />);

    expect(await screen.findByText("Backend unavailable")).toBeInTheDocument();
  });

  it("shows the Unattributed bucket without a drill-down affordance at the source level", async () => {
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: null,
          attributed: false,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 500,
          customerCount: 1,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);

    expect(await screen.findByText("Unattributed")).toBeInTheDocument();
    // Unattributed is a status badge, not a button -- there is no valid campaign/landing-page
    // comparison to drill into for revenue with no acquisition evidence at all.
    expect(
      screen.queryByRole("button", { name: "Unattributed" }),
    ).not.toBeInTheDocument();
  });

  it("shows the no-source-captured bucket as distinct from the Unattributed bucket, both present at once", async () => {
    // Regression: a strongly-attributed movement with no utm_source captured (e.g. direct traffic)
    // must never merge with, or be confused for, a genuinely unattributed movement -- both previously
    // shared the same null dimensionValue with no way to tell them apart.
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: null,
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 700,
          customerCount: 1,
        },
        {
          dimensionValue: null,
          attributed: false,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 300,
          customerCount: 1,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);

    expect(await screen.findByText("No source captured")).toBeInTheDocument();
    expect(screen.getByText("Unattributed")).toBeInTheDocument();
    expect(screen.getByText("$7")).toBeInTheDocument();
    expect(screen.getByText("$3")).toBeInTheDocument();
  });

  it("renders Retained MRR and NRR as an explicit unavailable state, never a number, when no cohort exists for the row", async () => {
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 2,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);

    await screen.findByText("google");
    expect(screen.getAllByText("Unavailable").length).toBe(2);
  });

  it("shows the maturity-pending reason and never a fabricated zero when the cohort hasn't matured", async () => {
    getSourceComparison.mockResolvedValue(
      comparison(
        [
          {
            dimensionValue: "google",
            attributed: true,
            currency: "USD",
            movementType: "NEW",
            totalMinor: 1000,
            customerCount: 2,
          },
        ],
        [
          {
            dimensionValue: "google",
            attributed: true,
            currency: "USD",
            startingMrrMinor: 1000,
            sampleSize: 2,
            cell: {
              available: false,
              unavailableReason: "MATURITY_PENDING",
              retainedMrrMinor: null,
              retentionPercentage: null,
              expansionMrrMinor: null,
              contractionMrrMinor: null,
              churnMrrMinor: null,
              reactivationMrrMinor: null,
              nrr: null,
            },
          },
        ],
      ),
    );

    render(<ComparisonTable {...baseProps} />);

    await screen.findByText("google");
    expect(screen.getAllByText("Unavailable").length).toBe(2);
    expect(
      screen.getByText(/hasn't reached 30 days old yet/i),
    ).toBeInTheDocument();
  });

  it("shows real Retained MRR and NRR once the cohort is mature", async () => {
    getSourceComparison.mockResolvedValue(
      comparison(
        [
          {
            dimensionValue: "google",
            attributed: true,
            currency: "USD",
            movementType: "NEW",
            totalMinor: 1000,
            customerCount: 2,
          },
        ],
        [
          {
            dimensionValue: "google",
            attributed: true,
            currency: "USD",
            startingMrrMinor: 1000,
            sampleSize: 2,
            cell: {
              available: true,
              unavailableReason: null,
              retainedMrrMinor: 900,
              retentionPercentage: 0.9,
              expansionMrrMinor: 0,
              contractionMrrMinor: 100,
              churnMrrMinor: 0,
              reactivationMrrMinor: 0,
              nrr: 0.9,
            },
          },
        ],
      ),
    );

    render(<ComparisonTable {...baseProps} />);

    await screen.findByText("google");
    expect(screen.getByText("$9")).toBeInTheDocument();
    expect(screen.getByText("(90%)")).toBeInTheDocument();
    expect(screen.getByText("90%")).toBeInTheDocument();
    expect(screen.queryByText("Unavailable")).not.toBeInTheDocument();
  });

  it("re-fetches at a different cohort age when a cohort-age button is clicked", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 2,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);
    await screen.findByText("google");

    await user.click(screen.getByRole("button", { name: "60d" }));

    expect(await screen.findByText("Retained MRR (60d)")).toBeInTheDocument();
    expect(getSourceComparison).toHaveBeenLastCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      "SOURCE",
      {
        source: undefined,
        campaign: undefined,
        campaignMissing: undefined,
        retentionAgeDays: 60,
      },
    );
  });

  it("calls onDrillDown when a source label is clicked", async () => {
    const user = userEvent.setup();
    const onDrillDown = vi.fn();
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 2,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} onDrillDown={onDrillDown} />);

    const label = await screen.findByRole("button", { name: "google" });
    await user.click(label);
    expect(onDrillDown).toHaveBeenCalledWith("google");
  });

  it("calls onSelectMetric when a New MRR amount is clicked", async () => {
    const user = userEvent.setup();
    const onSelectMetric = vi.fn();
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 2,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} onSelectMetric={onSelectMetric} />);

    const amount = await screen.findByRole("button", { name: "$10" });
    await user.click(amount);
    expect(onSelectMetric).toHaveBeenCalledWith({
      dimensionValue: "google",
      attributed: true,
      movementType: "NEW",
      currency: "USD",
    });
  });

  it("sorts rows by New MRR descending by default, and toggles direction on header click", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "small",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 500,
          customerCount: 1,
        },
        {
          dimensionValue: "big",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 2000,
          customerCount: 1,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);
    await screen.findByText("big");

    let rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]).getByText("big")).toBeInTheDocument();
    expect(within(rows[1]).getByText("small")).toBeInTheDocument();

    const newMrrHeader = screen.getByRole("columnheader", { name: /new mrr/i });
    await user.click(within(newMrrHeader).getByRole("button"));

    rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]).getByText("small")).toBeInTheDocument();
    expect(within(rows[1]).getByText("big")).toBeInTheDocument();
  });

  it("keeps currencies separate rather than summing them", async () => {
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "google",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 1000,
          customerCount: 1,
        },
        {
          dimensionValue: "google",
          attributed: true,
          currency: "EUR",
          movementType: "NEW",
          totalMinor: 800,
          customerCount: 1,
        },
      ]),
    );

    render(<ComparisonTable {...baseProps} />);

    await screen.findByText("USD");
    expect(screen.getByText("EUR")).toBeInTheDocument();
    expect(screen.getAllByText("google")).toHaveLength(2);
  });

  it("passes campaignMissing as an explicit boolean rather than a sentinel value in campaign", async () => {
    getSourceComparison.mockResolvedValue(
      comparison([
        {
          dimensionValue: "/landing-a",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 500,
          customerCount: 1,
        },
      ]),
    );

    render(
      <ComparisonTable
        {...baseProps}
        dimension="LANDING_PAGE"
        source="google"
        campaign={null}
        campaignMissing
      />,
    );

    await screen.findByText("/landing-a");

    expect(getSourceComparison).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      "LANDING_PAGE",
      {
        source: "google",
        campaign: undefined,
        campaignMissing: true,
        retentionAgeDays: 30,
      },
    );
  });
});
