import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type {
  RetentionAgeCell,
  RetentionCohortRow,
  RetentionCohorts,
} from "@/lib/api/types";

import { CohortHeatmap } from "./CohortHeatmap";

const { getRetentionCohorts } = vi.hoisted(() => ({
  getRetentionCohorts: vi.fn(),
}));

vi.mock("@/lib/api/reporting", () => ({ getRetentionCohorts }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const UNAVAILABLE: RetentionAgeCell = {
  available: false,
  unavailableReason: "MATURITY_PENDING",
  retainedMrrMinor: null,
  retentionPercentage: null,
  expansionMrrMinor: null,
  contractionMrrMinor: null,
  churnMrrMinor: null,
  reactivationMrrMinor: null,
  nrr: null,
};

const AVAILABLE_90PCT: RetentionAgeCell = {
  available: true,
  unavailableReason: null,
  retainedMrrMinor: 900,
  retentionPercentage: 0.9,
  expansionMrrMinor: 0,
  contractionMrrMinor: 100,
  churnMrrMinor: 0,
  reactivationMrrMinor: 0,
  nrr: 0.9,
};

function cohortRow(
  overrides: Partial<RetentionCohortRow> = {},
): RetentionCohortRow {
  return {
    dimensionValue: "google",
    attributed: true,
    currency: "USD",
    periodStart: "2026-01-01T00:00:00Z",
    periodEnd: "2026-02-01T00:00:00Z",
    startingMrrMinor: 1000,
    sampleSize: 2,
    age30: AVAILABLE_90PCT,
    age60: UNAVAILABLE,
    age90: UNAVAILABLE,
    ...overrides,
  };
}

function cohorts(rows: RetentionCohortRow[]): RetentionCohorts {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    dimension: "SOURCE",
    source: null,
    campaign: null,
    campaignMissing: false,
    cohorts: rows,
  };
}

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  dimension: "SOURCE" as const,
  source: null,
  campaign: null,
  campaignMissing: false,
  onDrillDown: vi.fn(),
};

describe("CohortHeatmap", () => {
  beforeEach(() => {
    getRetentionCohorts.mockReset();
  });

  it("shows a loading state, then the cohort rows", async () => {
    getRetentionCohorts.mockResolvedValue(cohorts([cohortRow()]));

    render(<CohortHeatmap {...baseProps} />);

    expect(
      screen.getByLabelText(/loading retention cohorts/i),
    ).toBeInTheDocument();
    expect(await screen.findByText("google")).toBeInTheDocument();
  });

  it("shows an empty state with no cohorts", async () => {
    getRetentionCohorts.mockResolvedValue(cohorts([]));

    render(<CohortHeatmap {...baseProps} />);

    expect(
      await screen.findByText(/no acquisition cohorts yet/i),
    ).toBeInTheDocument();
  });

  it("shows a retry-safe error message when loading fails", async () => {
    getRetentionCohorts.mockRejectedValue(
      new ApiError(500, "server_error", "Backend unavailable"),
    );

    render(<CohortHeatmap {...baseProps} />);

    expect(await screen.findByText("Backend unavailable")).toBeInTheDocument();
  });

  it("renders an immature age as explicitly unavailable, never a fabricated zero, while a mature age shows real numbers", async () => {
    getRetentionCohorts.mockResolvedValue(cohorts([cohortRow()]));

    render(<CohortHeatmap {...baseProps} />);

    await screen.findByText("google");
    // age60/age90 are immature: two "Unavailable" cells, never "$0" or "0%".
    expect(screen.getAllByText("Unavailable")).toHaveLength(2);
    expect(screen.queryByText("$0")).not.toBeInTheDocument();
    // age30 is mature: the real retained amount and percentage are shown.
    expect(screen.getByText("$9")).toBeInTheDocument();
    expect(screen.getByText(/90% retained/)).toBeInTheDocument();
    expect(screen.getByText(/NRR 90%/)).toBeInTheDocument();
    expect(
      screen.getByTitle(/this cohort hasn't reached 60 days old yet/i),
    ).toBeInTheDocument();
  });

  it("shows the maturity footnote only when at least one row has an immature age", async () => {
    getRetentionCohorts.mockResolvedValue(
      cohorts([
        cohortRow({
          age30: AVAILABLE_90PCT,
          age60: AVAILABLE_90PCT,
          age90: AVAILABLE_90PCT,
        }),
      ]),
    );

    render(<CohortHeatmap {...baseProps} />);

    await screen.findByText("google");
    expect(screen.queryByText(/hasn't matured yet/i)).not.toBeInTheDocument();
  });

  it("distinguishes the Unattributed bucket from a strongly-attributed row with no source captured", async () => {
    getRetentionCohorts.mockResolvedValue(
      cohorts([
        cohortRow({ dimensionValue: null, attributed: false, sampleSize: 1 }),
        cohortRow({ dimensionValue: null, attributed: true, sampleSize: 1 }),
      ]),
    );

    render(<CohortHeatmap {...baseProps} />);

    expect(await screen.findByText("Unattributed")).toBeInTheDocument();
    expect(screen.getByText("No source captured")).toBeInTheDocument();
  });

  it("calls onDrillDown when a source label is clicked", async () => {
    const user = userEvent.setup();
    const onDrillDown = vi.fn();
    getRetentionCohorts.mockResolvedValue(cohorts([cohortRow()]));

    render(<CohortHeatmap {...baseProps} onDrillDown={onDrillDown} />);

    const label = await screen.findByRole("button", { name: "google" });
    await user.click(label);
    expect(onDrillDown).toHaveBeenCalledWith("google");
  });

  it("keeps currencies and acquisition periods separate as distinct rows", async () => {
    getRetentionCohorts.mockResolvedValue(
      cohorts([
        cohortRow({ currency: "USD" }),
        cohortRow({ currency: "EUR", startingMrrMinor: 800 }),
        cohortRow({
          periodStart: "2026-02-01T00:00:00Z",
          periodEnd: "2026-03-01T00:00:00Z",
        }),
      ]),
    );

    render(<CohortHeatmap {...baseProps} />);

    await screen.findByText("EUR");
    expect(screen.getAllByText("USD")).toHaveLength(2);
    expect(screen.getAllByText("Jan 2026")).toHaveLength(2);
    expect(screen.getByText("Feb 2026")).toBeInTheDocument();
  });
});
