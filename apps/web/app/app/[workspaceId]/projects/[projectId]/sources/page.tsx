import { notFound } from "next/navigation";

import { PeriodFilter } from "@/components/overview/PeriodFilter";
import { SourcesClient } from "@/components/sources/SourcesClient";
import { ApiError } from "@/lib/api/errors";
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
  searchParams: Promise<{ preset?: string }>;
}

/** #23's source/campaign/landing-page comparison, period/project-scoped like the Overview screen. */
export default async function ProjectSourcesPage({
  params,
  searchParams,
}: SourcesPageProps) {
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
      />
    </div>
  );
}
