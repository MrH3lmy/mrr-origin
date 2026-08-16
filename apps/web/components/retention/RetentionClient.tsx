"use client";

import { useState } from "react";

import { CoveragePanel } from "@/components/sources/CoveragePanel";
import { Panel } from "@/components/ui/Panel";
import type { AttributionCoverage, ComparisonDimension } from "@/lib/api/types";

import { CohortHeatmap } from "./CohortHeatmap";
import sourcesStyles from "../sources/Sources.module.css";

interface RetentionClientProps {
  workspaceId: string;
  projectId: string;
  /** Null when this signal couldn't be loaded -- rendered as its own degraded state, never blocking the rest of the page. */
  coverage: AttributionCoverage | null;
}

interface DrillPath {
  source: string | null;
  campaign: string | null;
  /** True when the "no campaign captured" bucket was drilled into explicitly, rather than a real campaign. */
  campaignMissing: boolean;
}

const ROOT_PATH: DrillPath = {
  source: null,
  campaign: null,
  campaignMissing: false,
};

function dimensionFor(path: DrillPath): ComparisonDimension {
  if (path.campaign !== null || path.campaignMissing) return "LANDING_PAGE";
  if (path.source !== null) return "CAMPAIGN";
  return "SOURCE";
}

/**
 * #25's Retention screen: the same source -> campaign -> landing-page drill-down hierarchy as #23's
 * Sources comparison (DESIGN_SYSTEM.md), applied to the retained-MRR cohort heatmap instead of the
 * New/Churned MRR table.
 */
export function RetentionClient({
  workspaceId,
  projectId,
  coverage,
}: RetentionClientProps) {
  const [path, setPath] = useState<DrillPath>(ROOT_PATH);

  const dimension = dimensionFor(path);

  function goToRoot() {
    setPath(ROOT_PATH);
  }

  function goToSource() {
    setPath({ source: path.source, campaign: null, campaignMissing: false });
  }

  function drillDown(dimensionValue: string | null) {
    if (dimension === "SOURCE") {
      // dimensionValue is never null here -- the Unattributed bucket is not drillable.
      setPath({
        source: dimensionValue,
        campaign: null,
        campaignMissing: false,
      });
    } else if (dimension === "CAMPAIGN") {
      setPath(
        dimensionValue === null
          ? { source: path.source, campaign: null, campaignMissing: true }
          : {
              source: path.source,
              campaign: dimensionValue,
              campaignMissing: false,
            },
      );
    }
  }

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <Panel
        title="Retained-MRR cohorts"
        subtitle="30/60/90-day revenue durability by acquisition source, campaign, and landing page. NRR is shown beside each retained-MRR cell."
      >
        <nav aria-label="Drill-down" className={sourcesStyles.breadcrumb}>
          {path.source === null ? (
            <span className={sourcesStyles.breadcrumbCurrent}>All sources</span>
          ) : (
            <button
              type="button"
              className={sourcesStyles.breadcrumbLink}
              onClick={goToRoot}
            >
              All sources
            </button>
          )}
          {path.source !== null ? (
            <>
              <span
                className={sourcesStyles.breadcrumbSeparator}
                aria-hidden="true"
              >
                /
              </span>
              {path.campaign === null && !path.campaignMissing ? (
                <span className={sourcesStyles.breadcrumbCurrent}>
                  {path.source}
                </span>
              ) : (
                <button
                  type="button"
                  className={sourcesStyles.breadcrumbLink}
                  onClick={goToSource}
                >
                  {path.source}
                </button>
              )}
            </>
          ) : null}
          {path.campaign !== null || path.campaignMissing ? (
            <>
              <span
                className={sourcesStyles.breadcrumbSeparator}
                aria-hidden="true"
              >
                /
              </span>
              <span className={sourcesStyles.breadcrumbCurrent}>
                {path.campaignMissing ? "No campaign captured" : path.campaign}
              </span>
            </>
          ) : null}
        </nav>

        <CohortHeatmap
          workspaceId={workspaceId}
          projectId={projectId}
          dimension={dimension}
          source={path.source}
          campaign={path.campaign}
          campaignMissing={path.campaignMissing}
          onDrillDown={drillDown}
        />
      </Panel>

      <CoveragePanel
        workspaceId={workspaceId}
        projectId={projectId}
        coverage={coverage}
      />
    </div>
  );
}
