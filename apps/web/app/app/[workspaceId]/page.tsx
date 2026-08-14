import { redirect } from "next/navigation";

import { ButtonLink } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/StateMessage";
import { createServerClient } from "@/lib/api/server-client";
import { listProjects } from "@/lib/api/workspaces";

interface WorkspacePageProps {
  params: Promise<{ workspaceId: string }>;
}

export default async function WorkspacePage({ params }: WorkspacePageProps) {
  const { workspaceId } = await params;
  const client = await createServerClient();
  const projects = await listProjects(client, workspaceId);

  if (projects.length > 0) {
    redirect(`/app/${workspaceId}/projects/${projects[0].id}`);
  }

  return (
    <div style={{ maxWidth: 560 }}>
      <EmptyState
        title="Create your first project"
        description="A project represents one tracked site or app: its tracker installation, allowed domains, and Stripe-derived MRR."
      >
        <ButtonLink variant="primary" href={`/app/${workspaceId}/projects/new`}>
          Create project
        </ButtonLink>
      </EmptyState>
    </div>
  );
}
