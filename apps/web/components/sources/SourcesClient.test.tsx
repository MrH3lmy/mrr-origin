import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  AttributionCoverage,
  MrrMovementEntry,
  SourceComparison,
} from "@/lib/api/types";

import { resolveSourcesDeepLink, SourcesClient } from "./SourcesClient";

const { getSourceComparison, listMrrMovements, getAttributionCoverage } =
  vi.hoisted(() => ({
    getSourceComparison: vi.fn(),
    listMrrMovements: vi.fn(),
    getAttributionCoverage: vi.fn(),
  }));

vi.mock("@/lib/api/reporting", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/reporting")>();
  return {
    ...actual,
    getSourceComparison,
    listMrrMovements,
    getAttributionCoverage,
  };
});
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
  from: "2026-04-01T00:00:00Z",
  to: "2026-05-01T00:00:00Z",
  coverage: fullCoverage,
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
    campaignMissing: false,
    rows: [
      {
        dimensionValue: "google",
        attributed: true,
        currency: "USD",
        movementType: "NEW",
        totalMinor: 1000,
        customerCount: 2,
      },
    ],
    retentionAgeDays: 30,
    retention: [],
    unavailableMetrics: [
      {
        metric: "RETAINED_MRR",
        reason:
          "Unavailable for one or more comparison rows: NO_ACQUISITION_COHORT.",
      },
      {
        metric: "NRR",
        reason:
          "Unavailable for one or more comparison rows: NO_ACQUISITION_COHORT.",
      },
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
        attributed: true,
        currency: "USD",
        movementType: "NEW",
        totalMinor: 700,
        customerCount: 1,
      },
      {
        dimensionValue: null,
        attributed: true,
        currency: "USD",
        movementType: "NEW",
        totalMinor: 300,
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
      {
        source: undefined,
        campaign: undefined,
        campaignMissing: undefined,
        retentionAgeDays: 30,
      },
    );
  });

  it("renders the attribution coverage panel alongside the comparison", async () => {
    getSourceComparison.mockResolvedValue(sourceComparison());

    render(<SourcesClient {...baseProps} />);

    await screen.findByText("google");
    expect(
      screen.getByText("4 of 4 eligible new customers attributed"),
    ).toBeInTheDocument();
    expect(screen.getByText("100%")).toBeInTheDocument();
  });

  it("renders an honest unavailable coverage state when the signal failed to load", async () => {
    getSourceComparison.mockResolvedValue(sourceComparison());

    render(<SourcesClient {...baseProps} coverage={null} />);

    await screen.findByText("google");
    expect(
      screen.getByText("Couldn't load attribution coverage. Try refreshing."),
    ).toBeInTheDocument();
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
      {
        source: "google",
        campaign: undefined,
        campaignMissing: undefined,
        retentionAgeDays: 30,
      },
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

  it("drills into the no-campaign-captured bucket using an explicit boolean, not a sentinel string", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValueOnce(sourceComparison());
    getSourceComparison.mockResolvedValueOnce(campaignComparison());
    getSourceComparison.mockResolvedValueOnce({
      ...campaignComparison(),
      dimension: "LANDING_PAGE",
      rows: [
        {
          dimensionValue: "https://example.test/no-campaign-landing",
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 300,
          customerCount: 1,
        },
      ],
    });

    render(<SourcesClient {...baseProps} />);

    await user.click(await screen.findByRole("button", { name: "google" }));
    await screen.findByText("spring_sale");

    const noCampaignLabel = screen.getByRole("button", {
      name: "No campaign captured",
    });
    await user.click(noCampaignLabel);

    expect(
      await screen.findByText("https://example.test/no-campaign-landing"),
    ).toBeInTheDocument();
    expect(getSourceComparison).toHaveBeenLastCalledWith(
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
    expect(
      screen.getByText("No campaign captured", { selector: "span" }),
    ).toBeInTheDocument();
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

  it("filters evidence to the Unattributed bucket using an explicit sourceUnattributed boolean", async () => {
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue({
      ...sourceComparison(),
      rows: [
        {
          dimensionValue: null,
          attributed: false,
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
      expect.objectContaining({
        source: undefined,
        sourceUnattributed: true,
        sourceMissing: undefined,
      }),
    );
  });

  it("filters evidence to the no-source-captured bucket using sourceMissing, distinct from Unattributed", async () => {
    // Regression: both the Unattributed bucket and the no-source-captured bucket have
    // dimensionValue===null; without the `attributed` discriminator they were indistinguishable,
    // and clicking one could silently reconcile against the other's movements.
    const user = userEvent.setup();
    getSourceComparison.mockResolvedValue({
      ...sourceComparison(),
      rows: [
        {
          dimensionValue: null,
          attributed: true,
          currency: "USD",
          movementType: "NEW",
          totalMinor: 600,
          customerCount: 1,
        },
      ],
    });
    listMrrMovements.mockResolvedValue({ entries: [], nextCursor: null });

    render(<SourcesClient {...baseProps} />);

    const amount = await screen.findByRole("button", { name: "$6" });
    await user.click(amount);

    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({
        source: undefined,
        sourceUnattributed: undefined,
        sourceMissing: true,
      }),
    );
  });
});

describe("resolveSourcesDeepLink", () => {
  it("resolves to the root path with no selection when no source is present", () => {
    expect(resolveSourcesDeepLink({})).toEqual({
      path: { source: null, campaign: null, campaignMissing: false },
      selectedMetric: null,
    });
  });

  it("resolves a SOURCE-level real value, distinguishing it from sourceMissing/sourceUnattributed", () => {
    expect(
      resolveSourcesDeepLink({
        source: "google",
        movementType: "NEW",
        currency: "USD",
      }),
    ).toEqual({
      path: { source: null, campaign: null, campaignMissing: false },
      selectedMetric: {
        dimensionValue: "google",
        attributed: true,
        movementType: "NEW",
        currency: "USD",
      },
    });
  });

  it("resolves the SOURCE-level Unattributed bucket via the explicit boolean, not a sentinel value", () => {
    expect(
      resolveSourcesDeepLink({
        sourceUnattributed: true,
        movementType: "CHURN",
        currency: "USD",
      }),
    ).toEqual({
      path: { source: null, campaign: null, campaignMissing: false },
      selectedMetric: {
        dimensionValue: null,
        attributed: false,
        movementType: "CHURN",
        currency: "USD",
      },
    });
  });

  it("resolves a CAMPAIGN-level deep link, scoping the drill path to the parent source", () => {
    expect(
      resolveSourcesDeepLink({
        source: "google",
        campaign: "spring_sale",
        movementType: "NEW",
        currency: "USD",
      }),
    ).toEqual({
      path: { source: "google", campaign: null, campaignMissing: false },
      selectedMetric: {
        dimensionValue: "spring_sale",
        attributed: true,
        movementType: "NEW",
        currency: "USD",
      },
    });
  });

  it("resolves a LANDING_PAGE-level deep link within the no-campaign-captured bucket", () => {
    expect(
      resolveSourcesDeepLink({
        source: "google",
        campaignMissing: true,
        landingPage: "/pricing",
        movementType: "NEW",
        currency: "EUR",
      }),
    ).toEqual({
      path: { source: "google", campaign: null, campaignMissing: true },
      selectedMetric: {
        dimensionValue: "/pricing",
        attributed: true,
        movementType: "NEW",
        currency: "EUR",
      },
    });
  });

  it("resolves a drill path with no metric selection when movementType/currency are absent", () => {
    expect(resolveSourcesDeepLink({ source: "google" })).toEqual({
      path: { source: null, campaign: null, campaignMissing: false },
      selectedMetric: null,
    });
  });
});
