import { redirect } from "next/navigation";

import { ButtonLink } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/StateMessage";
import { createServerClient } from "@/lib/api/server-client";
import { listWorkspaces } from "@/lib/api/workspaces";

export const metadata = { title: "MRROrigin" };

export default async function AppRootPage() {
  const client = await createServerClient();
  const workspaces = await listWorkspaces(client);

  if (workspaces.length > 0) {
    redirect(`/app/${workspaces[0].id}`);
  }

  return (
    <div style={{ maxWidth: 560 }}>
      <EmptyState
        title="Create your first workspace"
        description="A workspace holds your projects, Stripe connection, and team members. Founders usually need just one."
      >
        <ButtonLink variant="primary" href="/app/workspaces/new">
          Create workspace
        </ButtonLink>
      </EmptyState>
    </div>
  );
}
