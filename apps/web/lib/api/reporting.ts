import type { ApiClient } from "./client";
import type {
  AttributionCoverage,
  MrrMovementsPage,
  MrrMovementType,
  RevenueOverview,
} from "./types";

function base(workspaceId: string, projectId: string): string {
  return `/workspaces/${workspaceId}/projects/${projectId}`;
}

export function getRevenueOverview(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  from: string,
  to: string,
): Promise<RevenueOverview> {
  const params = new URLSearchParams({ from, to });
  return client.get<RevenueOverview>(
    `${base(workspaceId, projectId)}/reporting/overview?${params}`,
  );
}

export interface ListMovementsOptions {
  movementType?: MrrMovementType;
  source?: string;
  /** Restricts to one currency -- required for a drill-down to reconcile with a currency-specific summary row. */
  currency?: string;
  cursor?: string;
  limit?: number;
}

export function listMrrMovements(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  from: string,
  to: string,
  options: ListMovementsOptions = {},
): Promise<MrrMovementsPage> {
  const params = new URLSearchParams({ from, to });
  if (options.movementType) params.set("movementType", options.movementType);
  if (options.source) params.set("source", options.source);
  if (options.currency) params.set("currency", options.currency);
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.limit) params.set("limit", String(options.limit));
  return client.get<MrrMovementsPage>(
    `${base(workspaceId, projectId)}/reporting/movements?${params}`,
  );
}

export function getAttributionCoverage(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<AttributionCoverage> {
  return client.get<AttributionCoverage>(
    `${base(workspaceId, projectId)}/attribution/coverage`,
  );
}
