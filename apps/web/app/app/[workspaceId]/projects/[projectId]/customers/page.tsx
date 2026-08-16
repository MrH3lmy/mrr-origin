import { notFound } from "next/navigation";

import { CustomersClient } from "@/components/customers/CustomersClient";
import { ApiError } from "@/lib/api/errors";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";

interface CustomersPageProps {
  params: Promise<{ workspaceId: string; projectId: string }>;
}

/** #24's customer index/search entry point. */
export default async function ProjectCustomersPage({
  params,
}: CustomersPageProps) {
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
          Customers
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

      <CustomersClient workspaceId={workspaceId} projectId={projectId} />
    </div>
  );
}
