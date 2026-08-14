import "server-only";

import { cookies } from "next/headers";

/**
 * Minimal, real bearer-token session. Production identity-provider and session/BFF topology is an
 * explicit deferred architecture decision (see ARCHITECTURE.md "Deferred decisions") and no local
 * IdP runs in this environment (compose.yaml only provisions Postgres) -- this module intentionally
 * does not decide that. It only defines the seam every downstream page needs: a real, httpOnly,
 * server-only access token that is attached to every backend request and produces real 401 handling.
 * See `app/auth/sign-in/page.tsx` for how the token is currently obtained in local development.
 */
const SESSION_COOKIE = "mrr_session";

export interface Session {
  accessToken: string;
}

export async function getSession(): Promise<Session | null> {
  const store = await cookies();
  const token = store.get(SESSION_COOKIE)?.value;
  if (!token) {
    return null;
  }
  return { accessToken: token };
}

export async function setSessionCookie(accessToken: string): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE, accessToken, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 60 * 60 * 8,
  });
}

export async function clearSessionCookie(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE);
}

export { SESSION_COOKIE };
