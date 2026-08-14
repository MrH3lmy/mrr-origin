"use client";

import { useRouter } from "next/navigation";

import type { Project } from "@/lib/api/types";

import styles from "./AppShell.module.css";

interface ProjectSwitcherProps {
  workspaceId: string;
  projects: Project[];
  currentProjectId?: string;
}

export function ProjectSwitcher({
  workspaceId,
  projects,
  currentProjectId,
}: ProjectSwitcherProps) {
  const router = useRouter();

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
          router.push(`/app/${workspaceId}/projects/${event.target.value}`);
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
