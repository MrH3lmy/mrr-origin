import type { ApiClient } from "./client";
import type {
  StripeBillingHealthReport,
  StripeConnection,
  StripeConnectionMode,
} from "./types";

export function getStripeConnection(
  client: ApiClient,
  workspaceId: string,
): Promise<StripeConnection> {
  return client.get<StripeConnection>(
    `/workspaces/${workspaceId}/stripe-connection`,
  );
}

export function startStripeOauth(
  client: ApiClient,
  workspaceId: string,
  mode: StripeConnectionMode,
): Promise<{ authorizationUrl: string }> {
  return client.post<{ authorizationUrl: string }>(
    `/workspaces/${workspaceId}/stripe-connection/oauth/start`,
    { mode },
  );
}

export function disconnectStripe(
  client: ApiClient,
  workspaceId: string,
): Promise<StripeConnection> {
  return client.del<StripeConnection>(
    `/workspaces/${workspaceId}/stripe-connection`,
  );
}

export function getStripeHealth(
  client: ApiClient,
  workspaceId: string,
): Promise<StripeBillingHealthReport> {
  return client.get<StripeBillingHealthReport>(
    `/workspaces/${workspaceId}/stripe-connection/health`,
  );
}

export function resumeBackfill(
  client: ApiClient,
  workspaceId: string,
): Promise<{
  pagesProcessed: number;
  phase: string;
  complete: boolean;
  connectionEligible: boolean;
}> {
  return client.post(
    `/workspaces/${workspaceId}/stripe-connection/backfill/resume`,
  );
}
