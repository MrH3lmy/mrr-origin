import { NextRequest, NextResponse } from "next/server";

import { setSessionCookie } from "@/lib/auth/session";

/** Stores a bearer access token as an httpOnly session cookie. See `app/auth/sign-in/page.tsx`. */
export async function POST(request: NextRequest) {
  const form = await request.formData();
  const token = form.get("accessToken");

  if (typeof token !== "string" || token.trim().length === 0) {
    const url = new URL("/auth/sign-in", request.url);
    url.searchParams.set("error", "missing_token");
    return NextResponse.redirect(url, { status: 303 });
  }

  await setSessionCookie(token.trim());
  const redirectTo = form.get("redirectTo");
  const destination =
    typeof redirectTo === "string" && redirectTo.startsWith("/")
      ? redirectTo
      : "/app";
  return NextResponse.redirect(new URL(destination, request.url), {
    status: 303,
  });
}
