import { notFound } from "next/navigation";

import { WeeklySummaryClient } from "@/components/weekly-summary/WeeklySummaryClient";
import { ApiError } from "@/lib/api/errors";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";

interface WeeklySummaryPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
}

/**
 * #26's weekly action summary: the last completed project-timezone week's New/Churned MRR
 * material-change signals per source/campaign/landing-page, each linking to the exact Sources (#23)
 * evidence that produced it.
 */
export default async function ProjectWeeklySummaryPage({
  params,
}: WeeklySummaryPageProps) {
  const { workspaceId, projectId } = await params;

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
      <div>
        <h1
          style={{
            margin: "0 0 4px",
            fontSize: "1.75rem",
            letterSpacing: "-0.01em",
          }}
        >
          Weekly summary
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

      <WeeklySummaryClient workspaceId={workspaceId} projectId={projectId} />
    </div>
  );
}
