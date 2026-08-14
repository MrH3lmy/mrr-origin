import { notFound } from "next/navigation";

import { ProjectStatusView } from "@/components/onboarding/ProjectStatusView";
import { ApiError } from "@/lib/api/errors";
import { createServerClient } from "@/lib/api/server-client";
import { getStripeHealth } from "@/lib/api/stripe";
import {
  getActiveIngestionKey,
  getDiagnostics,
  getVerificationStatus,
  listAllowedDomains,
} from "@/lib/api/tracking";
import { getProject } from "@/lib/api/workspaces";
import type { VerificationAttempt } from "@/lib/api/types";

interface ProjectPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
}

export default async function ProjectPage({ params }: ProjectPageProps) {
  const { workspaceId, projectId } = await params;
  const client = await createServerClient();

  let project;
  try {
    project = await getProject(client, workspaceId, projectId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const [activeKey, allowedDomains, diagnostics, stripeHealth] =
    await Promise.all([
      getActiveIngestionKey(client, workspaceId, projectId),
      listAllowedDomains(client, workspaceId, projectId),
      getDiagnostics(client, workspaceId, projectId),
      getStripeHealth(client, workspaceId),
    ]);

  let verification: VerificationAttempt | null = null;
  try {
    verification = await getVerificationStatus(client, workspaceId, projectId);
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) throw error;
  }

  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 880 }}>
      <div>
        <h1
          style={{
            margin: "0 0 4px",
            fontSize: "1.75rem",
            letterSpacing: "-0.01em",
          }}
        >
          {project.name}
        </h1>
        <p
          style={{
            margin: 0,
            color: "var(--ds-text-muted)",
            fontSize: "0.875rem",
          }}
        >
          {project.domain}
        </p>
      </div>

      <ProjectStatusView
        workspaceId={workspaceId}
        projectId={projectId}
        project={project}
        initialActiveKey={activeKey}
        initialDomains={allowedDomains}
        initialDiagnostics={diagnostics}
        initialVerification={verification}
        initialHealth={stripeHealth}
      />
    </div>
  );
}
