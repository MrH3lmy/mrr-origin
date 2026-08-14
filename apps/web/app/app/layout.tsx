import { redirect } from "next/navigation";
import type { ReactNode } from "react";

import { AppShell } from "@/components/app-shell/AppShell";
import { ApiError } from "@/lib/api/errors";
import { createServerClient } from "@/lib/api/server-client";
import { listWorkspaces } from "@/lib/api/workspaces";
import { clearSessionCookie, getSession } from "@/lib/auth/session";

import "./app-shell.css";

export default async function AuthenticatedAppLayout({
  children,
}: {
  children: ReactNode;
}) {
  const session = await getSession();
  if (!session) {
    redirect("/auth/sign-in?redirectTo=/app");
  }

  const client = await createServerClient();
  let workspaces;
  try {
    workspaces = await listWorkspaces(client);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      await clearSessionCookie();
      redirect("/auth/sign-in?redirectTo=/app&error=session_expired");
    }
    throw error;
  }

  return <AppShell initialWorkspaces={workspaces}>{children}</AppShell>;
}
