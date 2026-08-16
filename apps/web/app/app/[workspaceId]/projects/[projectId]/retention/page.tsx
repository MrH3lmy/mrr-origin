import { notFound } from "next/navigation";

import { RetentionClient } from "@/components/retention/RetentionClient";
import { ApiError } from "@/lib/api/errors";
import { getAttributionCoverage } from "@/lib/api/reporting";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";

interface RetentionPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
}

/**
 * #25's 30/60/90-day retained-MRR cohort screen. Unlike Overview/Sources, this screen is not
 * period-filtered: the cohort heatmap groups by each customer's own acquisition month, so every
 * acquisition period is shown at once rather than re-windowed by a report date range.
 */
export default async function ProjectRetentionPage({
  params,
}: RetentionPageProps) {
  const { workspaceId, projectId } = await params;

  const client = await createServerClient();

  let project;
  try {
    project = await getProject(client, workspaceId, projectId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  // Attribution coverage is a supporting data-quality signal, not the primary content -- a
  // transient failure here must not take down the whole retention screen (or silently pretend
  // coverage is 100%), matching the Sources screen's precedent for the same read model.
  const coverage = await getAttributionCoverage(
    client,
    workspaceId,
    projectId,
  ).catch(() => null);

  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <div>
        <h1
          style={{
            margin: "0 0 4px",
            fontSize: "1.75rem",
            letterSpacing: "-0.01em",
          }}
        >
          Retention
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

      <RetentionClient
        workspaceId={workspaceId}
        projectId={projectId}
        coverage={coverage}
      />
    </div>
  );
}
