import { notFound } from "next/navigation";

import { ButtonLink } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ApiError } from "@/lib/api/errors";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";

interface OverviewPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
}

/**
 * Deliberately not #22's overview dashboard (out of scope for #21). This confirms setup completed
 * and points back at Data health, so a healthy founder isn't left on a dead end after onboarding.
 */
export default async function ProjectOverviewPage({
  params,
}: OverviewPageProps) {
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
    <div style={{ maxWidth: 560 }}>
      <Panel
        title={project.name}
        subtitle={project.domain}
        actions={<StatusBadge tone="positive">Ready</StatusBadge>}
      >
        <p
          style={{ margin: "0 0 16px", fontSize: "0.875rem", lineHeight: 1.6 }}
        >
          Setup is complete — tracker installation and Stripe are both healthy.
          Revenue-attribution reporting (source comparison, retention cohorts,
          and the weekly summary) lands in a later release; this project is
          ready for that data to appear as it&rsquo;s calculated.
        </p>
        <ButtonLink
          variant="secondary"
          href={`/app/${workspaceId}/projects/${projectId}`}
        >
          Back to Data health
        </ButtonLink>
      </Panel>
    </div>
  );
}
