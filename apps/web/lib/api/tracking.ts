import type { ApiClient } from "./client";
import type {
  ActiveIngestionKey,
  AllowedDomain,
  IssuedIngestionKey,
  ProjectDiagnosticsReport,
  VerificationAttempt,
} from "./types";

function base(workspaceId: string, projectId: string): string {
  return `/workspaces/${workspaceId}/projects/${projectId}`;
}

export function getActiveIngestionKey(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<ActiveIngestionKey> {
  return client.get<ActiveIngestionKey>(
    `${base(workspaceId, projectId)}/tracking/ingestion-key`,
  );
}

export function issueOrRotateIngestionKey(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<IssuedIngestionKey> {
  return client.post<IssuedIngestionKey>(
    `${base(workspaceId, projectId)}/tracking/ingestion-key`,
  );
}

export function listAllowedDomains(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<AllowedDomain[]> {
  return client.get<AllowedDomain[]>(
    `${base(workspaceId, projectId)}/tracking/allowed-domains`,
  );
}

export function addAllowedDomain(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  domain: string,
): Promise<AllowedDomain> {
  return client.post<AllowedDomain>(
    `${base(workspaceId, projectId)}/tracking/allowed-domains`,
    { domain },
  );
}

export function removeAllowedDomain(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
  domainId: string,
): Promise<void> {
  return client.del(
    `${base(workspaceId, projectId)}/tracking/allowed-domains/${domainId}`,
  );
}

export function startVerification(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<VerificationAttempt> {
  return client.post<VerificationAttempt>(
    `${base(workspaceId, projectId)}/tracking/verification`,
  );
}

export function getVerificationStatus(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<VerificationAttempt> {
  return client.get<VerificationAttempt>(
    `${base(workspaceId, projectId)}/tracking/verification`,
  );
}

export function getDiagnostics(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<ProjectDiagnosticsReport> {
  return client.get<ProjectDiagnosticsReport>(
    `${base(workspaceId, projectId)}/tracking/diagnostics`,
  );
}
