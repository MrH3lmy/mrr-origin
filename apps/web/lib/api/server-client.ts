import "server-only";

import { getSession } from "@/lib/auth/session";

import { ApiError } from "./errors";
import { buildClient, type ApiClient } from "./client";

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

/** Client for use in server components/layouts: calls the backend directly with the session's bearer token. */
export async function createServerClient(): Promise<ApiClient> {
  const session = await getSession();
  if (!session) {
    throw new ApiError(401, "unauthenticated", "Sign-in is required");
  }
  return buildClient(`${API_BASE_URL}/api`, () => ({
    Authorization: `Bearer ${session.accessToken}`,
  }));
}
