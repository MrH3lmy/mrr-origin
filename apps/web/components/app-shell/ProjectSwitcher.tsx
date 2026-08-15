"use client";

import { usePathname, useRouter } from "next/navigation";

import type { Project } from "@/lib/api/types";

import styles from "./AppShell.module.css";

interface ProjectSwitcherProps {
  workspaceId: string;
  projects: Project[];
  currentProjectId?: string;
}

/**
 * The trailing path after `/projects/{id}` (e.g. `/overview`), so switching projects from a
 * sub-page (like Overview) lands on the same sub-page for the newly selected project instead of
 * always resetting to the Data health root.
 */
export function currentSubPath(
  pathname: string,
  workspaceId: string,
  projectId?: string,
): string {
  if (!projectId) return "";
  const prefix = `/app/${workspaceId}/projects/${projectId}`;
  return pathname.startsWith(prefix) ? pathname.slice(prefix.length) : "";
}

export function ProjectSwitcher({
  workspaceId,
  projects,
  currentProjectId,
}: ProjectSwitcherProps) {
  const router = useRouter();
  const pathname = usePathname();

  return (
    <div>
      <label className={styles.switcherLabel} htmlFor="project-switcher">
        Project
      </label>
      <select
        id="project-switcher"
        className={styles.select}
        value={currentProjectId ?? ""}
        disabled={projects.length === 0}
        onChange={(event) => {
          if (event.target.value === "__new__") {
            router.push(`/app/${workspaceId}/projects/new`);
            return;
          }
          const subPath = currentSubPath(
            pathname,
            workspaceId,
            currentProjectId,
          );
          router.push(
            `/app/${workspaceId}/projects/${event.target.value}${subPath}`,
          );
        }}
      >
        {projects.length === 0 ? (
          <option value="">No projects yet</option>
        ) : null}
        {projects.map((project) => (
          <option key={project.id} value={project.id}>
            {project.name}
          </option>
        ))}
        <option value="__new__">+ New project</option>
      </select>
    </div>
  );
}
