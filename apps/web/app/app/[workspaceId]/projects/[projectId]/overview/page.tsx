import { notFound } from "next/navigation";

import { OverviewClient } from "@/components/overview/OverviewClient";
import { PeriodFilter } from "@/components/overview/PeriodFilter";
import { ApiError } from "@/lib/api/errors";
import {
  getAttributionCoverage,
  getRevenueOverview,
} from "@/lib/api/reporting";
import { createServerClient } from "@/lib/api/server-client";
import { getStripeHealth } from "@/lib/api/stripe";
import { getDiagnostics } from "@/lib/api/tracking";
import { getProject } from "@/lib/api/workspaces";
import {
  DEFAULT_PERIOD_PRESET,
  isPeriodPreset,
  resolvePeriod,
  type PeriodPreset,
} from "@/lib/period";

interface OverviewPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
  searchParams: Promise<{ preset?: string }>;
}

/** #22's founder overview: period/project-scoped MRR movement, coverage, and data-health reporting. */
export default async function ProjectOverviewPage({
  params,
  searchParams,
}: OverviewPageProps) {
  const { workspaceId, projectId } = await params;
  const { preset: rawPreset } = await searchParams;
  const preset: PeriodPreset = isPeriodPreset(rawPreset)
    ? rawPreset
    : DEFAULT_PERIOD_PRESET;
  const { from, to } = resolvePeriod(preset);

  const client = await createServerClient();

  let project;
  try {
    project = await getProject(client, workspaceId, projectId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const [overview, coverage, stripeHealth, diagnostics] = await Promise.all([
    getRevenueOverview(client, workspaceId, projectId, from, to),
    getAttributionCoverage(client, workspaceId, projectId),
    getStripeHealth(client, workspaceId),
    getDiagnostics(client, workspaceId, projectId),
  ]);

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
            Overview
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

      <OverviewClient
        workspaceId={workspaceId}
        projectId={projectId}
        from={from}
        to={to}
        overview={overview}
        coverage={coverage}
        stripeHealth={stripeHealth}
        diagnostics={diagnostics}
      />
    </div>
  );
}
