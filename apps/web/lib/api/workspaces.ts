import type { ApiClient } from "./client";
import type { Project, Workspace } from "./types";

export function listWorkspaces(client: ApiClient): Promise<Workspace[]> {
  return client.get<Workspace[]>("/workspaces");
}

export function getWorkspace(
  client: ApiClient,
  workspaceId: string,
): Promise<Workspace> {
  return client.get<Workspace>(`/workspaces/${workspaceId}`);
}

export function createWorkspace(
  client: ApiClient,
  input: { name: string; slug: string; reportingCurrency?: string },
): Promise<Workspace> {
  return client.post<Workspace>("/workspaces", input);
}

export function listProjects(
  client: ApiClient,
  workspaceId: string,
): Promise<Project[]> {
  return client.get<Project[]>(`/workspaces/${workspaceId}/projects`);
}

export function getProject(
  client: ApiClient,
  workspaceId: string,
  projectId: string,
): Promise<Project> {
  return client.get<Project>(
    `/workspaces/${workspaceId}/projects/${projectId}`,
  );
}

export function createProject(
  client: ApiClient,
  workspaceId: string,
  input: { name: string; domain: string; timezone?: string },
): Promise<Project> {
  return client.post<Project>(`/workspaces/${workspaceId}/projects`, input);
}
