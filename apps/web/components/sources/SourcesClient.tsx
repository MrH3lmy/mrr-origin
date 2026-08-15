"use client";

import { useState } from "react";

import { Panel } from "@/components/ui/Panel";
import { MovementsDrilldown } from "@/components/overview/MovementsDrilldown";
import type { ComparisonDimension } from "@/lib/api/types";

import { ComparisonTable, type MetricSelection } from "./ComparisonTable";
import styles from "./Sources.module.css";

interface SourcesClientProps {
  workspaceId: string;
  projectId: string;
  from: string;
  to: string;
}

interface DrillPath {
  source: string | null;
  campaign: string | null;
}

const ROOT_PATH: DrillPath = { source: null, campaign: null };

function dimensionFor(path: DrillPath): ComparisonDimension {
  if (path.campaign !== null) return "LANDING_PAGE";
  if (path.source !== null) return "CAMPAIGN";
  return "SOURCE";
}

export function SourcesClient({
  workspaceId,
  projectId,
  from,
  to,
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
    setPath({ source: path.source, campaign: null });
    setSelectedMetric(null);
  }

  function drillDown(dimensionValue: string | null) {
    if (dimension === "SOURCE") {
      // dimensionValue is never null here -- the Unattributed bucket is not drillable.
      setPath({ source: dimensionValue, campaign: null });
    } else if (dimension === "CAMPAIGN") {
      setPath({ source: path.source, campaign: dimensionValue ?? "NONE" });
    }
    setSelectedMetric(null);
  }

  function selectMetric(selection: MetricSelection) {
    setSelectedMetric((current) =>
      current?.dimensionValue === selection.dimensionValue &&
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
  const evidence = (() => {
    if (selectedMetric) {
      if (dimension === "SOURCE") {
        return {
          source:
            selectedMetric.dimensionValue === null
              ? "UNATTRIBUTED"
              : selectedMetric.dimensionValue,
          campaign: null,
          landingPage: null,
        };
      }
      if (dimension === "CAMPAIGN") {
        return {
          source: path.source,
          campaign: selectedMetric.dimensionValue ?? "NONE",
          landingPage: null,
        };
      }
      return {
        source: path.source,
        campaign: path.campaign,
        landingPage: selectedMetric.dimensionValue ?? "NONE",
      };
    }
    if (path.source === null) return null;
    return { source: path.source, campaign: path.campaign, landingPage: null };
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
              {path.campaign === null ? (
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
          {path.campaign !== null ? (
            <>
              <span className={styles.breadcrumbSeparator} aria-hidden="true">
                /
              </span>
              <span className={styles.breadcrumbCurrent}>
                {path.campaign === "NONE"
                  ? "No campaign captured"
                  : path.campaign}
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
          onDrillDown={drillDown}
          onSelectMetric={selectMetric}
          selectedMetric={selectedMetric}
        />
      </Panel>

      <MovementsDrilldown
        workspaceId={workspaceId}
        projectId={projectId}
        from={from}
        to={to}
        movementType={selectedMetric?.movementType ?? null}
        source={evidence?.source ?? null}
        campaign={evidence?.campaign ?? null}
        landingPage={evidence?.landingPage ?? null}
        currency={selectedMetric?.currency ?? null}
        onClearFilters={() => setSelectedMetric(null)}
      />
    </div>
  );
}
