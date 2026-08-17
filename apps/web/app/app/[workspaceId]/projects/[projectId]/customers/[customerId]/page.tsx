import Link from "next/link";
import { notFound } from "next/navigation";

import { CustomerTimelineClient } from "@/components/customers/CustomerTimelineClient";
import { ApiError } from "@/lib/api/errors";
import { getCustomerTimeline } from "@/lib/api/customers";
import { createServerClient } from "@/lib/api/server-client";
import { getProject } from "@/lib/api/workspaces";

interface CustomerDetailPageProps {
  params: Promise<{
    workspaceId: string;
    projectId: string;
    customerId: string;
  }>;
}

/**
 * #24's customer detail/evidence timeline. A customer ID this project does not own resolves to a
 * real 404 (matching the API), not a client-rendered error -- an unknown or cross-project customer
 * ID looks identical either way.
 */
export default async function CustomerDetailPage({
  params,
}: CustomerDetailPageProps) {
  const { workspaceId, projectId, customerId } = await params;

  const client = await createServerClient();

  let project;
  try {
    project = await getProject(client, workspaceId, projectId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  let timeline;
  try {
    timeline = await getCustomerTimeline(
      client,
      workspaceId,
      projectId,
      customerId,
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  return (
    <div style={{ display: "grid", gap: 24, maxWidth: 1040 }}>
      <div>
        <Link
          href={`/app/${workspaceId}/projects/${projectId}/customers`}
          style={{ fontSize: "0.8125rem", color: "var(--ds-text-muted)" }}
        >
          ← Customers
        </Link>
        <h1
          style={{
            margin: "8px 0 4px",
            fontSize: "1.75rem",
            letterSpacing: "-0.01em",
          }}
        >
          {customerId}
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

      <CustomerTimelineClient
        workspaceId={workspaceId}
        projectId={projectId}
        stripeCustomerId={customerId}
        initialTimeline={timeline}
      />
    </div>
  );
}
