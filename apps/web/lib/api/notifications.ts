import type { ApiClient } from "./client";
import type { WeeklySummaryDelivery, WeeklySummaryOptOut } from "./types";

function base(workspaceId: string, projectId: string): string {
  return `/workspaces/${workspaceId}/projects/${projectId}/notifications/weekly-summary`;
}

// -- Per-member weekly-summary opt-out (#59) --

export function getWeeklySummaryOptOut(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<WeeklySummaryOptOut> {
  return client.get<WeeklySummaryOptOut>(
    `${base(workspaceId, projectId)}/opt-out`,
  );
}

export function setWeeklySummaryOptOut(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  optedOut: boolean,
): Promise<WeeklySummaryOptOut> {
  return client.put<WeeklySummaryOptOut>(
    `${base(workspaceId, projectId)}/opt-out`,
    {
      optedOut,
    },
  );
}

// -- Manager-only manual send trigger and delivery status (#59) --

export function sendWeeklySummaryNow(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<{ triggeredAt: string }> {
  return client.post<{ triggeredAt: string }>(
    `${base(workspaceId, projectId)}/send`,
  );
}

export function listWeeklySummaryDeliveries(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<WeeklySummaryDelivery[]> {
  return client.get<WeeklySummaryDelivery[]>(
    `${base(workspaceId, projectId)}/deliveries`,
  );
}

/**
 * Manual replay of a terminal delivery (#59, accepted B3/B5 corrections): a `PERMANENTLY_FAILED`
 * row gets a fresh attempt budget; a `BLOCKED_MISSING_EMAIL` row is only replayed once the member
 * has a verified email (a 409 response means it still doesn't).
 */
export function replayWeeklySummaryDelivery(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  deliveryId: string,
): Promise<{ triggeredAt: string }> {
  return client.post<{ triggeredAt: string }>(
    `${base(workspaceId, projectId)}/deliveries/${deliveryId}/replay`,
  );
}
