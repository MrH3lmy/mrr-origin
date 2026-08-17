"use client";

import { useState } from "react";

import { ExportLink } from "@/components/ui/ExportLink";
import { Panel } from "@/components/ui/Panel";
import { MovementsDrilldown } from "@/components/overview/MovementsDrilldown";
import {
  comparisonExportUrl,
  retentionCohortsExportUrl,
} from "@/lib/api/reporting";
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
  /** Deep-link starting point (e.g. from a #26 weekly-summary evidence link). Defaults to the root. */
  initialPath?: DrillPath;
  initialSelectedMetric?: MetricSelection | null;
}

export interface DrillPath {
  source: string | null;
  campaign: string | null;
  /** True when the "no campaign captured" bucket was drilled into explicitly, rather than a real campaign. */
  campaignMissing: boolean;
}

export const ROOT_PATH: DrillPath = {
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
 * Resolves the same explicit boolean-mode evidence filters #26's weekly summary (and #22's
 * `/movements` endpoint) use into this component's drill path + selected metric, so opening a
 * summary insight's evidence link preselects the exact comparison cell and movement drilldown that
 * produced it -- never a looser filter. A real value, `*Missing`, and `*Unattributed` are always
 * distinguished by an explicit boolean, so `"NONE"`/`"UNATTRIBUTED"` bucket labels can never collide
 * with an actual source/campaign/landing-page string of that name.
 */
export function resolveSourcesDeepLink(params: {
  movementType?: string | null;
  currency?: string | null;
  source?: string | null;
  sourceUnattributed?: boolean;
  sourceMissing?: boolean;
  campaign?: string | null;
  campaignMissing?: boolean;
  landingPage?: string | null;
  landingPageMissing?: boolean;
}): { path: DrillPath; selectedMetric: MetricSelection | null } {
  const hasSource =
    !!params.source || !!params.sourceMissing || !!params.sourceUnattributed;
  if (!hasSource) {
    return { path: ROOT_PATH, selectedMetric: null };
  }
  const hasCampaign = !!params.campaign || !!params.campaignMissing;
  const hasLandingPage = !!params.landingPage || !!params.landingPageMissing;
  const canSelectMetric = !!params.movementType && !!params.currency;
  const movementType = params.movementType as MetricSelection["movementType"];

  if (hasLandingPage) {
    const path: DrillPath = {
      source: params.source ?? null,
      campaign: params.campaign ?? null,
      campaignMissing: !!params.campaignMissing,
    };
    return {
      path,
      selectedMetric: canSelectMetric
        ? {
            dimensionValue: params.landingPage ?? null,
            attributed: true,
            movementType,
            currency: params.currency as string,
          }
        : null,
    };
  }
  if (hasCampaign) {
    const path: DrillPath = {
      source: params.source ?? null,
      campaign: null,
      campaignMissing: false,
    };
    return {
      path,
      selectedMetric: canSelectMetric
        ? {
            dimensionValue: params.campaign ?? null,
            attributed: true,
            movementType,
            currency: params.currency as string,
          }
        : null,
    };
  }
  return {
    path: ROOT_PATH,
    selectedMetric: canSelectMetric
      ? {
          dimensionValue: params.source ?? null,
          attributed: !params.sourceUnattributed,
          movementType,
          currency: params.currency as string,
        }
      : null,
  };
}

export function SourcesClient({
  workspaceId,
  projectId,
  from,
  to,
  coverage,
  initialPath = ROOT_PATH,
  initialSelectedMetric = null,
}: SourcesClientProps) {
  const [path, setPath] = useState<DrillPath>(initialPath);
  const [selectedMetric, setSelectedMetric] = useState<MetricSelection | null>(
    initialSelectedMetric,
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
        subtitle="New MRR, Churned MRR, customer count, and 30/60/90-day Retained MRR / NRR for the selected period."
        actions={
          <div style={{ display: "flex", gap: 8 }}>
            <ExportLink
              href={comparisonExportUrl(
                workspaceId,
                projectId,
                from,
                to,
                dimension,
                {
                  source: path.source ?? undefined,
                  campaign: path.campaign ?? undefined,
                  campaignMissing: path.campaignMissing || undefined,
                },
              )}
            >
              Export comparison CSV
            </ExportLink>
            <ExportLink
              href={retentionCohortsExportUrl(
                workspaceId,
                projectId,
                dimension,
                {
                  source: path.source ?? undefined,
                  campaign: path.campaign ?? undefined,
                  campaignMissing: path.campaignMissing || undefined,
                },
              )}
            >
              Export retention cohorts CSV
            </ExportLink>
          </div>
        }
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
