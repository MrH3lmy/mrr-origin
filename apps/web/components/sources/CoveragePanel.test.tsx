import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { AttributionCoverage } from "@/lib/api/types";

import { CoveragePanel } from "./CoveragePanel";

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

describe("CoveragePanel", () => {
  it("shows the coverage ratio and counts when fully attributed", () => {
    render(
      <CoveragePanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={fullCoverage}
      />,
    );

    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(
      screen.getByText("4 of 4 eligible new customers attributed"),
    ).toBeInTheDocument();
  });

  it("humanizes exclusion reasons when coverage is partial", () => {
    render(
      <CoveragePanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={partialCoverage}
      />,
    );

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

  it("never labels partial coverage as a danger/failure state", () => {
    render(
      <CoveragePanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={partialCoverage}
      />,
    );

    expect(screen.getByText("50%").className).not.toMatch(/danger/i);
  });

  it("renders an honest unavailable state when coverage failed to load, without crashing", () => {
    render(
      <CoveragePanel workspaceId="ws-1" projectId="proj-1" coverage={null} />,
    );

    expect(
      screen.getByText("Couldn't load attribution coverage. Try refreshing."),
    ).toBeInTheDocument();
    expect(screen.getByText("Unavailable")).toBeInTheDocument();
  });

  it("links to Data health for full detail", () => {
    render(
      <CoveragePanel
        workspaceId="ws-1"
        projectId="proj-1"
        coverage={fullCoverage}
      />,
    );

    expect(
      screen.getByRole("link", { name: "Open Data health" }),
    ).toHaveAttribute("href", "/app/ws-1/projects/proj-1");
  });
});
