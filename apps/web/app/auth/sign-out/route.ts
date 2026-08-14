import { NextRequest, NextResponse } from "next/server";

import { clearSessionCookie } from "@/lib/auth/session";

export async function POST(request: NextRequest) {
  await clearSessionCookie();
  return NextResponse.redirect(new URL("/auth/sign-in", request.url), {
    status: 303,
  });
}
