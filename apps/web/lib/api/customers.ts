import type { ApiClient } from "./client";
import type {
  CustomerDirectoryPage,
  CustomerTimeline,
  RepairCustomerLinkResponse,
} from "./types";

function base(workspaceId: string, projectId: string): string {
  return `/workspaces/${workspaceId}/projects/${projectId}`;
}

export interface ListCustomersOptions {
  search?: string;
  cursor?: string;
  limit?: number;
}

export function listCustomers(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  options: ListCustomersOptions = {},
): Promise<CustomerDirectoryPage> {
  const params = new URLSearchParams();
  if (options.search) params.set("search", options.search);
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.limit) params.set("limit", String(options.limit));
  const query = params.toString();
  return client.get<CustomerDirectoryPage>(
    `${base(workspaceId, projectId)}/customers${query ? `?${query}` : ""}`,
  );
}

export interface GetCustomerTimelineOptions {
  cursor?: string;
  limit?: number;
}

export function getCustomerTimeline(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  stripeCustomerId: string,
  options: GetCustomerTimelineOptions = {},
): Promise<CustomerTimeline> {
  const params = new URLSearchParams();
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.limit) params.set("limit", String(options.limit));
  const query = params.toString();
  return client.get<CustomerTimeline>(
    `${base(workspaceId, projectId)}/customers/${encodeURIComponent(stripeCustomerId)}/timeline${query ? `?${query}` : ""}`,
  );
}

/** #20's manual create-or-correct repair, reused as-is -- no second repair mechanism. */
export function repairCustomerLink(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  externalUserId: string,
  stripeCustomerId: string,
): Promise<RepairCustomerLinkResponse> {
  return client.post<RepairCustomerLinkResponse>(
    `${base(workspaceId, projectId)}/unattributed-revenue/repairs`,
    { externalUserId, stripeCustomerId },
  );
}
