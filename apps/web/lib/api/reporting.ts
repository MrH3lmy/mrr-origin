import type { ApiClient } from "./client";
import type {
  AttributionCoverage,
  ComparisonDimension,
  MrrMovementsPage,
  MrrMovementType,
  RevenueOverview,
  SourceComparison,
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
  /** Requires `source`. Mutually exclusive with `campaignMissing`. */
  campaign?: string;
  /** Selects the "no campaign captured" bucket explicitly. Mutually exclusive with `campaign`. */
  campaignMissing?: boolean;
  /** Requires `campaign` or `campaignMissing`. Mutually exclusive with `landingPageMissing`. */
  landingPage?: string;
  /** Selects the "no landing page captured" bucket explicitly. Mutually exclusive with `landingPage`. */
  landingPageMissing?: boolean;
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
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  if (options.landingPage) params.set("landingPage", options.landingPage);
  if (options.landingPageMissing) params.set("landingPageMissing", "true");
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

export interface GetSourceComparisonOptions {
  /** Required for CAMPAIGN and LANDING_PAGE dimensions. */
  source?: string;
  /** Required for LANDING_PAGE (with `campaignMissing`). Mutually exclusive with `campaignMissing`. */
  campaign?: string;
  /** Selects the "no campaign captured" bucket explicitly. Mutually exclusive with `campaign`. */
  campaignMissing?: boolean;
}

export function getSourceComparison(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  from: string,
  to: string,
  dimension: ComparisonDimension,
  options: GetSourceComparisonOptions = {},
): Promise<SourceComparison> {
  const params = new URLSearchParams({ from, to, dimension });
  if (options.source) params.set("source", options.source);
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  return client.get<SourceComparison>(
    `${base(workspaceId, projectId)}/reporting/comparison?${params}`,
  );
}
