import type { ApiClient } from "./client";
import type {
  AttributionCoverage,
  ComparisonDimension,
  MrrMovementsPage,
  MrrMovementType,
  RetentionAgeDays,
  RetentionCohorts,
  RetentionSummary,
  RevenueOverview,
  SourceComparison,
  WeeklySummary,
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
  /** A real source value. Mutually exclusive with `sourceUnattributed` and `sourceMissing`. */
  source?: string;
  /** Selects movements with no acquisition evidence at all. Mutually exclusive with `source`/`sourceMissing`. */
  sourceUnattributed?: boolean;
  /** Selects strongly-attributed movements whose touchpoint captured no source (e.g. direct traffic). Mutually exclusive with `source`/`sourceUnattributed`. */
  sourceMissing?: boolean;
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
  if (options.sourceUnattributed) params.set("sourceUnattributed", "true");
  if (options.sourceMissing) params.set("sourceMissing", "true");
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
  /** The retained-MRR/NRR cohort age to join in. Defaults to 30 server-side. */
  retentionAgeDays?: RetentionAgeDays;
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
  if (options.retentionAgeDays)
    params.set("retentionAgeDays", String(options.retentionAgeDays));
  return client.get<SourceComparison>(
    `${base(workspaceId, projectId)}/reporting/comparison?${params}`,
  );
}

// -- 30/60/90-day retained-MRR cohorts (#25) --

export interface GetRetentionCohortsOptions {
  /** Required for CAMPAIGN and LANDING_PAGE dimensions. */
  source?: string;
  /** Required for LANDING_PAGE (with `campaignMissing`). Mutually exclusive with `campaignMissing`. */
  campaign?: string;
  /** Selects the "no campaign captured" bucket explicitly. Mutually exclusive with `campaign`. */
  campaignMissing?: boolean;
}

export function getRetentionCohorts(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  dimension: ComparisonDimension,
  options: GetRetentionCohortsOptions = {},
): Promise<RetentionCohorts> {
  const params = new URLSearchParams({ dimension });
  if (options.source) params.set("source", options.source);
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  return client.get<RetentionCohorts>(
    `${base(workspaceId, projectId)}/reporting/retention/cohorts?${params}`,
  );
}

export interface GetRetentionSummaryOptions extends GetRetentionCohortsOptions {
  ageDays?: RetentionAgeDays;
}

export function getRetentionSummary(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  from: string,
  to: string,
  dimension: ComparisonDimension,
  options: GetRetentionSummaryOptions = {},
): Promise<RetentionSummary> {
  const params = new URLSearchParams({ from, to, dimension });
  if (options.source) params.set("source", options.source);
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  if (options.ageDays) params.set("ageDays", String(options.ageDays));
  return client.get<RetentionSummary>(
    `${base(workspaceId, projectId)}/reporting/retention/summary?${params}`,
  );
}

// -- Weekly action summary (#26) --

/** ISO date (YYYY-MM-DD) of a Monday in the project's timezone. Defaults server-side to the last completed week. */
export function getWeeklySummary(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  weekStart?: string,
): Promise<WeeklySummary> {
  const params = weekStart ? `?${new URLSearchParams({ weekStart })}` : "";
  return client.get<WeeklySummary>(
    `${base(workspaceId, projectId)}/reporting/weekly-summary${params}`,
  );
}

// -- CSV exports (#26) --
//
// These return same-origin proxy paths (not fetched via ApiClient) so a plain <a href> download
// link carries the session's httpOnly cookie the same way every other proxied request does -- no
// token ever needs to reach browser JS to authorize an export.

export interface ComparisonExportOptions {
  source?: string;
  campaign?: string;
  campaignMissing?: boolean;
  retentionAgeDays?: RetentionAgeDays;
}

export function comparisonExportUrl(
  workspaceId: string,
  projectId: string,
  from: string,
  to: string,
  dimension: ComparisonDimension,
  options: ComparisonExportOptions = {},
): string {
  const params = new URLSearchParams({ from, to, dimension });
  if (options.source) params.set("source", options.source);
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  if (options.retentionAgeDays)
    params.set("retentionAgeDays", String(options.retentionAgeDays));
  return `/api/proxy/workspaces/${workspaceId}/projects/${projectId}/reporting/exports/comparison?${params}`;
}

export interface RetentionCohortsExportOptions {
  source?: string;
  campaign?: string;
  campaignMissing?: boolean;
}

export function retentionCohortsExportUrl(
  workspaceId: string,
  projectId: string,
  dimension: ComparisonDimension,
  options: RetentionCohortsExportOptions = {},
): string {
  const params = new URLSearchParams({ dimension });
  if (options.source) params.set("source", options.source);
  if (options.campaign) params.set("campaign", options.campaign);
  if (options.campaignMissing) params.set("campaignMissing", "true");
  return `/api/proxy/workspaces/${workspaceId}/projects/${projectId}/reporting/exports/retention-cohorts?${params}`;
}

export function customersExportUrl(
  workspaceId: string,
  projectId: string,
): string {
  return `/api/proxy/workspaces/${workspaceId}/projects/${projectId}/reporting/exports/customers`;
}
