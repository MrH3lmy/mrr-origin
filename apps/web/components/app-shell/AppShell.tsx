"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { createBrowserClient } from "@/lib/api/client";
import { listProjects } from "@/lib/api/workspaces";
import type { Project, Workspace } from "@/lib/api/types";

import styles from "./AppShell.module.css";
import { ProjectSwitcher } from "./ProjectSwitcher";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

interface AppShellProps {
  initialWorkspaces: Workspace[];
  children: ReactNode;
}

const COMING_SOON_ITEMS = ["Retention", "Customers", "Settings"];

// "workspaces" and "new" are excluded from their capture groups: /app/workspaces/new and
// /app/{workspaceId}/projects/new are reserved creation routes, not scoped to a real workspace or
// project, even though they match the same URL shape as one.
const ROUTE_PATTERN =
  /^\/app\/(?!workspaces(?:\/|$))([^/]+)(?:\/projects\/(?!new(?:\/|$))([^/]+))?/;

export function parseRoute(pathname: string): {
  workspaceId?: string;
  projectId?: string;
} {
  const match = ROUTE_PATTERN.exec(pathname);
  if (!match) return {};
  return { workspaceId: match[1], projectId: match[2] };
}

export function AppShell({ initialWorkspaces, children }: AppShellProps) {
  const pathname = usePathname();
  const { workspaceId: currentWorkspaceId, projectId: currentProjectId } =
    parseRoute(pathname);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [fetchedProjects, setFetchedProjects] = useState<{
    workspaceId: string;
    projects: Project[];
  } | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  // Reset drawer/focus state as a render-time adjustment (React's recommended alternative to an
  // effect that only mirrors a prop) rather than a synchronous setState inside an effect body.
  const [drawerClosedForPathname, setDrawerClosedForPathname] =
    useState(pathname);
  if (pathname !== drawerClosedForPathname) {
    setDrawerClosedForPathname(pathname);
    if (drawerOpen) setDrawerOpen(false);
  }

  useEffect(() => {
    if (drawerOpen) {
      closeButtonRef.current?.focus();
    }
  }, [drawerOpen]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && drawerOpen) {
        setDrawerOpen(false);
        menuButtonRef.current?.focus();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen]);

  useEffect(() => {
    if (!currentWorkspaceId) return;
    let cancelled = false;
    listProjects(createBrowserClient(), currentWorkspaceId)
      .then((result) => {
        if (!cancelled)
          setFetchedProjects({
            workspaceId: currentWorkspaceId,
            projects: result,
          });
      })
      .catch(() => {
        if (!cancelled)
          setFetchedProjects({ workspaceId: currentWorkspaceId, projects: [] });
      });
    return () => {
      cancelled = true;
    };
    // Re-syncs on every navigation (not just when currentWorkspaceId first becomes truthy) so the
    // switcher picks up a project created via /app/{workspaceId}/projects/new -- AppShell stays
    // mounted across in-workspace navigation, so nothing else would otherwise invalidate this list.
  }, [currentWorkspaceId, pathname]);

  const projects =
    currentWorkspaceId && fetchedProjects?.workspaceId === currentWorkspaceId
      ? fetchedProjects.projects
      : undefined;

  const dataHealthHref =
    currentWorkspaceId && currentProjectId
      ? `/app/${currentWorkspaceId}/projects/${currentProjectId}`
      : undefined;
  const isDataHealthActive =
    Boolean(dataHealthHref) && pathname === dataHealthHref;

  const overviewHref =
    currentWorkspaceId && currentProjectId
      ? `/app/${currentWorkspaceId}/projects/${currentProjectId}/overview`
      : undefined;
  const isOverviewActive = Boolean(overviewHref) && pathname === overviewHref;

  const sourcesHref =
    currentWorkspaceId && currentProjectId
      ? `/app/${currentWorkspaceId}/projects/${currentProjectId}/sources`
      : undefined;
  const isSourcesActive = Boolean(sourcesHref) && pathname === sourcesHref;

  const sidebar = (
    <nav
      id="primary-navigation"
      className={`${styles.sidebar} ${drawerOpen ? styles.sidebarOpen : ""}`}
      aria-label="Primary"
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <Link href="/app" className={styles.brand}>
          <span className={styles.mark} aria-hidden="true">
            M
          </span>
          MRROrigin
        </Link>
        <button
          type="button"
          ref={closeButtonRef}
          className={styles.menuButton}
          onClick={() => setDrawerOpen(false)}
          style={{ display: drawerOpen ? undefined : "none" }}
        >
          Close
        </button>
      </div>

      {currentWorkspaceId ? (
        <div className={styles.switchers}>
          <WorkspaceSwitcher
            workspaces={initialWorkspaces}
            currentWorkspaceId={currentWorkspaceId}
          />
          {projects ? (
            <ProjectSwitcher
              workspaceId={currentWorkspaceId}
              projects={projects}
              currentProjectId={currentProjectId}
            />
          ) : null}
        </div>
      ) : null}

      <div>
        <p className={styles.navGroupLabel}>MRROrigin</p>
        <ul className={styles.navGroup}>
          <li>
            {overviewHref ? (
              <Link
                href={overviewHref}
                className={`${styles.navLink} ${isOverviewActive ? styles.navLinkActive : ""}`}
                aria-current={isOverviewActive ? "page" : undefined}
              >
                Overview
              </Link>
            ) : (
              <span className={styles.navLinkDisabled}>Overview</span>
            )}
          </li>
          <li>
            {sourcesHref ? (
              <Link
                href={sourcesHref}
                className={`${styles.navLink} ${isSourcesActive ? styles.navLinkActive : ""}`}
                aria-current={isSourcesActive ? "page" : undefined}
              >
                Sources
              </Link>
            ) : (
              <span className={styles.navLinkDisabled}>Sources</span>
            )}
          </li>
          <li>
            {dataHealthHref ? (
              <Link
                href={dataHealthHref}
                className={`${styles.navLink} ${isDataHealthActive ? styles.navLinkActive : ""}`}
                aria-current={isDataHealthActive ? "page" : undefined}
              >
                Data health
              </Link>
            ) : (
              <span className={styles.navLinkDisabled}>Data health</span>
            )}
          </li>
          {COMING_SOON_ITEMS.map((item) => (
            <li key={item}>
              <span className={styles.navLinkDisabled}>
                {item}
                <span className={styles.soonBadge}>Soon</span>
              </span>
            </li>
          ))}
        </ul>
      </div>

      <div className={styles.footer}>
        <form method="POST" action="/auth/sign-out">
          <button type="submit" className={styles.signOut}>
            Sign out
          </button>
        </form>
      </div>
    </nav>
  );

  return (
    <div className="app-shell">
      <a href="#main" className="skipLink">
        Skip to content
      </a>
      <div className={styles.shell}>
        <div className={styles.topbar}>
          <Link href="/app" className={styles.brand}>
            <span className={styles.mark} aria-hidden="true">
              M
            </span>
            MRROrigin
          </Link>
          <button
            type="button"
            ref={menuButtonRef}
            className={styles.menuButton}
            aria-expanded={drawerOpen}
            aria-controls="primary-navigation"
            onClick={() => setDrawerOpen(true)}
          >
            Menu
          </button>
        </div>

        {drawerOpen ? (
          <div
            className={styles.backdrop}
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
        ) : null}

        {sidebar}

        <main id="main" className={styles.main} tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  );
}
