import { notFound } from "next/navigation";

import { PeriodFilter } from "@/components/overview/PeriodFilter";
import {
  resolveSourcesDeepLink,
  SourcesClient,
} from "@/components/sources/SourcesClient";
import { ApiError } from "@/lib/api/errors";
import { getAttributionCoverage } from "@/lib/api/reporting";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";
import {
  DEFAULT_PERIOD_PRESET,
  isPeriodPreset,
  resolvePeriod,
  type PeriodPreset,
} from "@/lib/period";

interface SourcesPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
  /**
   * `preset` drives the normal period picker. The rest are #26 evidence-link deep-link filters --
   * present together only when arriving from a weekly-summary insight link -- carrying the exact
   * boolean-mode evidence filter set `EvidenceLink`/`RevenueMovementsService` use, so this page can
   * preselect the precise comparison cell and movement drilldown that produced that insight. `from`/
   * `to`, when present, override `preset` with the linked insight's exact week boundaries.
   */
  searchParams: Promise<{
    preset?: string;
    from?: string;
    to?: string;
    movementType?: string;
    currency?: string;
    source?: string;
    sourceUnattributed?: string;
    sourceMissing?: string;
    campaign?: string;
    campaignMissing?: string;
    landingPage?: string;
    landingPageMissing?: string;
  }>;
}

/** #23's source/campaign/landing-page comparison, period/project-scoped like the Overview screen. */
export default async function ProjectSourcesPage({
  params,
  searchParams,
}: SourcesPageProps) {
  const { workspaceId, projectId } = await params;
  const search = await searchParams;
  const preset: PeriodPreset = isPeriodPreset(search.preset)
    ? search.preset
    : DEFAULT_PERIOD_PRESET;
  const { from: presetFrom, to: presetTo } = resolvePeriod(preset);
  const from = search.from ?? presetFrom;
  const to = search.to ?? presetTo;
  const { path: initialPath, selectedMetric: initialSelectedMetric } =
    resolveSourcesDeepLink({
      movementType: search.movementType,
      currency: search.currency,
      source: search.source,
      sourceUnattributed: search.sourceUnattributed === "true",
      sourceMissing: search.sourceMissing === "true",
      campaign: search.campaign,
      campaignMissing: search.campaignMissing === "true",
      landingPage: search.landingPage,
      landingPageMissing: search.landingPageMissing === "true",
    });

  const client = await createServerClient();

  let project;
  try {
    project = await getProject(client, workspaceId, projectId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  // Attribution coverage is a supporting data-quality signal, not the primary content -- a
  // transient failure here must not take down the whole comparison screen (or silently pretend
  // coverage is 100%). CoveragePanel renders an honest "couldn't load" state on null, matching
  // DataHealthPanel's precedent for the same read model on the Overview screen.
  const coverage = await getAttributionCoverage(
    client,
    workspaceId,
    projectId,
  ).catch(() => null);

  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 16,
          alignItems: "flex-end",
          justifyContent: "space-between",
        }}
      >
        <div>
          <h1
            style={{
              margin: "0 0 4px",
              fontSize: "1.75rem",
              letterSpacing: "-0.01em",
            }}
          >
            Sources
          </h1>
          <p
            style={{
              margin: 0,
              color: "var(--ds-text-muted)",
              fontSize: "0.875rem",
            }}
          >
            {project.name} · {project.domain}
          </p>
        </div>
        <PeriodFilter value={preset} />
      </div>

      <SourcesClient
        workspaceId={workspaceId}
        projectId={projectId}
        from={from}
        to={to}
        coverage={coverage}
        initialPath={initialPath}
        initialSelectedMetric={initialSelectedMetric}
      />
    </div>
  );
}
