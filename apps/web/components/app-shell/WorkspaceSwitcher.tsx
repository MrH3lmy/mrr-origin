"use client";

import { useRouter } from "next/navigation";

import type { Workspace } from "@/lib/api/types";

import styles from "./AppShell.module.css";

interface WorkspaceSwitcherProps {
  workspaces: Workspace[];
  currentWorkspaceId: string;
}

export function WorkspaceSwitcher({
  workspaces,
  currentWorkspaceId,
}: WorkspaceSwitcherProps) {
  const router = useRouter();

  return (
    <div>
      <label className={styles.switcherLabel} htmlFor="workspace-switcher">
        Workspace
      </label>
      <select
        id="workspace-switcher"
        className={styles.select}
        value={currentWorkspaceId}
        onChange={(event) => {
          if (event.target.value === "__new__") {
            router.push("/app/workspaces/new");
            return;
          }
          router.push(`/app/${event.target.value}`);
        }}
      >
        {workspaces.map((workspace) => (
          <option key={workspace.id} value={workspace.id}>
            {workspace.name}
          </option>
        ))}
        <option value="__new__">+ New workspace</option>
      </select>
    </div>
  );
}
