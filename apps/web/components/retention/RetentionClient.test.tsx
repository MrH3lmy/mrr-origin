import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AttributionCoverage, RetentionCohorts } from "@/lib/api/types";

import { RetentionClient } from "./RetentionClient";

const { getRetentionCohorts } = vi.hoisted(() => ({
  getRetentionCohorts: vi.fn(),
}));

vi.mock("@/lib/api/reporting", () => ({ getRetentionCohorts }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const fullCoverage: AttributionCoverage = {
  modelVersion: "attribution-v1",
  eligibleNewCustomers: 4,
  attributedNewCustomers: 4,
  coverageRatio: 1,
  exclusionReasonCounts: {},
};

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  coverage: fullCoverage,
};

function sourceCohorts(): RetentionCohorts {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    dimension: "SOURCE",
    source: null,
    campaign: null,
    campaignMissing: false,
    cohorts: [
      {
        dimensionValue: "google",
        attributed: true,
        currency: "USD",
        periodStart: "2026-01-01T00:00:00Z",
        periodEnd: "2026-02-01T00:00:00Z",
        startingMrrMinor: 1000,
        sampleSize: 2,
        age30: {
          available: true,
          unavailableReason: null,
          retainedMrrMinor: 1000,
          retentionPercentage: 1,
          expansionMrrMinor: 0,
          contractionMrrMinor: 0,
          churnMrrMinor: 0,
          reactivationMrrMinor: 0,
          nrr: 1,
        },
        age60: {
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
        age90: {
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
  };
}

function campaignCohorts(): RetentionCohorts {
  return {
    ...sourceCohorts(),
    dimension: "CAMPAIGN",
    source: "google",
    cohorts: [{ ...sourceCohorts().cohorts[0], dimensionValue: "spring_sale" }],
  };
}

describe("RetentionClient", () => {
  beforeEach(() => {
    getRetentionCohorts.mockReset();
  });

  it("starts at the source dimension with 'All sources' as the current breadcrumb", async () => {
    getRetentionCohorts.mockResolvedValue(sourceCohorts());

    render(<RetentionClient {...baseProps} />);

    expect(await screen.findByText("google")).toBeInTheDocument();
    expect(screen.getByText("All sources")).toBeInTheDocument();
    expect(getRetentionCohorts).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      "SOURCE",
      { source: undefined, campaign: undefined, campaignMissing: undefined },
    );
  });

  it("renders the attribution coverage panel alongside the cohort heatmap", async () => {
    getRetentionCohorts.mockResolvedValue(sourceCohorts());

    render(<RetentionClient {...baseProps} />);

    await screen.findByText("google");
    expect(
      screen.getByText("4 of 4 eligible new customers attributed"),
    ).toBeInTheDocument();
  });

  it("renders an honest unavailable coverage state when the signal failed to load", async () => {
    getRetentionCohorts.mockResolvedValue(sourceCohorts());

    render(<RetentionClient {...baseProps} coverage={null} />);

    await screen.findByText("google");
    expect(
      screen.getByText("Couldn't load attribution coverage. Try refreshing."),
    ).toBeInTheDocument();
  });

  it("drills into campaigns within a source when its label is clicked, and back out via the breadcrumb", async () => {
    const user = userEvent.setup();
    getRetentionCohorts.mockResolvedValueOnce(sourceCohorts());
    getRetentionCohorts.mockResolvedValueOnce(campaignCohorts());

    render(<RetentionClient {...baseProps} />);

    const sourceLabel = await screen.findByRole("button", { name: "google" });
    await user.click(sourceLabel);

    expect(await screen.findByText("spring_sale")).toBeInTheDocument();
    expect(getRetentionCohorts).toHaveBeenLastCalledWith(
      {},
      "ws-1",
      "proj-1",
      "CAMPAIGN",
      { source: "google", campaign: undefined, campaignMissing: undefined },
    );
    expect(
      screen.getByRole("button", { name: "All sources" }),
    ).toBeInTheDocument();

    getRetentionCohorts.mockResolvedValueOnce(sourceCohorts());
    await user.click(screen.getByRole("button", { name: "All sources" }));
    expect(await screen.findByText("All sources")).toBeInTheDocument();
  });
});
