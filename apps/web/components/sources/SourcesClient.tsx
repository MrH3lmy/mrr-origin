"use client";

import { useState } from "react";

import { Panel } from "@/components/ui/Panel";
import { MovementsDrilldown } from "@/components/overview/MovementsDrilldown";
import type { AttributionCoverage, ComparisonDimension } from "@/lib/api/types";

import { ComparisonTable, type MetricSelection } from "./ComparisonTable";
import { CoveragePanel } from "./CoveragePanel";
import styles from "./Sources.module.css";

interface SourcesClientProps {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
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

export function SourcesClient({
  workspaceId,
  projectId,
  from,
  to,
  coverage,
}: SourcesClientProps) {
  const [path, setPath] = useState<DrillPath>(ROOT_PATH);
  const [selectedMetric, setSelectedMetric] = useState<MetricSelection | null>(
    null,
  );

  const dimension = dimensionFor(path);

  function goToRoot() {
    setPath(ROOT_PATH);
    setSelectedMetric(null);
  }

  function goToSource() {
    setPath({ source: path.source, campaign: null, campaignMissing: false });
    setSelectedMetric(null);
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
    setSelectedMetric(null);
  }

  function selectMetric(selection: MetricSelection) {
    setSelectedMetric((current) =>
      current?.dimensionValue === selection.dimensionValue &&
      current?.attributed === selection.attributed &&
      current?.movementType === selection.movementType &&
      current?.currency === selection.currency
        ? null
        : selection,
    );
  }

  // Resolves the current drill path plus the selected metric row into the exact evidence filters
  // #22's movement drill-down endpoint accepts, so the table below always reconciles to whichever
  // row/cell the founder clicked -- never a looser or different filter than what produced the number.
  // Without a specific row/cell selected, evidence still stays scoped to the drilled-into path
  // (rather than showing every movement in the project) so the two panels never visibly disagree.
  // "Missing" buckets (no campaign/landing page captured) are always represented as an explicit
  // boolean, never a sentinel string, so a real value can never collide with the missing-value case.
  const evidence = (() => {
    if (selectedMetric) {
      if (dimension === "SOURCE") {
        return {
          source: selectedMetric.dimensionValue,
          // Two distinct null-source buckets, each an explicit boolean rather than a sentinel value
          // in `source`, so a real source string can never collide with either.
          sourceUnattributed:
            selectedMetric.dimensionValue === null &&
            !selectedMetric.attributed,
          sourceMissing:
            selectedMetric.dimensionValue === null && selectedMetric.attributed,
          campaign: null,
          campaignMissing: false,
          landingPage: null,
          landingPageMissing: false,
        };
      }
      if (dimension === "CAMPAIGN") {
        return {
          source: path.source,
          sourceUnattributed: false,
          sourceMissing: false,
          campaign: selectedMetric.dimensionValue,
          campaignMissing: selectedMetric.dimensionValue === null,
          landingPage: null,
          landingPageMissing: false,
        };
      }
      return {
        source: path.source,
        sourceUnattributed: false,
        sourceMissing: false,
        campaign: path.campaign,
        campaignMissing: path.campaignMissing,
        landingPage: selectedMetric.dimensionValue,
        landingPageMissing: selectedMetric.dimensionValue === null,
      };
    }
    if (path.source === null) return null;
    return {
      source: path.source,
      sourceUnattributed: false,
      sourceMissing: false,
      campaign: path.campaign,
      campaignMissing: path.campaignMissing,
      landingPage: null,
      landingPageMissing: false,
    };
  })();

  return (
    <div style={{ display: "grid", gap: 24 }}>
      <Panel
        title="Compare by source, campaign, and landing page"
        subtitle="New MRR, Churned MRR, and customer count for the selected period. Retained MRR and NRR ship with Retention (#25)."
      >
        <nav aria-label="Drill-down" className={styles.breadcrumb}>
          {path.source === null ? (
            <span className={styles.breadcrumbCurrent}>All sources</span>
          ) : (
            <button
              type="button"
              className={styles.breadcrumbLink}
              onClick={goToRoot}
            >
              All sources
            </button>
          )}
          {path.source !== null ? (
            <>
              <span className={styles.breadcrumbSeparator} aria-hidden="true">
                /
              </span>
              {path.campaign === null && !path.campaignMissing ? (
                <span className={styles.breadcrumbCurrent}>{path.source}</span>
              ) : (
                <button
                  type="button"
                  className={styles.breadcrumbLink}
                  onClick={goToSource}
                >
                  {path.source}
                </button>
              )}
            </>
          ) : null}
          {path.campaign !== null || path.campaignMissing ? (
            <>
              <span className={styles.breadcrumbSeparator} aria-hidden="true">
                /
              </span>
              <span className={styles.breadcrumbCurrent}>
                {path.campaignMissing ? "No campaign captured" : path.campaign}
              </span>
            </>
          ) : null}
        </nav>

        <ComparisonTable
          workspaceId={workspaceId}
          projectId={projectId}
          from={from}
          to={to}
          dimension={dimension}
          source={path.source}
          campaign={path.campaign}
          campaignMissing={path.campaignMissing}
          onDrillDown={drillDown}
          onSelectMetric={selectMetric}
          selectedMetric={selectedMetric}
        />
      </Panel>

      <CoveragePanel
        workspaceId={workspaceId}
        projectId={projectId}
        coverage={coverage}
      />

      <MovementsDrilldown
        workspaceId={workspaceId}
        projectId={projectId}
        from={from}
        to={to}
        movementType={selectedMetric?.movementType ?? null}
        source={evidence?.source ?? null}
        sourceUnattributed={evidence?.sourceUnattributed ?? false}
        sourceMissing={evidence?.sourceMissing ?? false}
        campaign={evidence?.campaign ?? null}
        campaignMissing={evidence?.campaignMissing ?? false}
        landingPage={evidence?.landingPage ?? null}
        landingPageMissing={evidence?.landingPageMissing ?? false}
        currency={selectedMetric?.currency ?? null}
        onClearFilters={() => setSelectedMetric(null)}
      />
    </div>
  );
}
