import { NextRequest, NextResponse } from "next/server";

import { getSession } from "@/lib/auth/session";

const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

/**
 * Same-origin proxy so client components can call the backend without ever holding the session
 * token in browser JS -- the token stays in the httpOnly cookie and is attached here, server-side.
 */
async function handle(
  req: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  const session = await getSession();
  if (!session) {
    return NextResponse.json(
      { code: "unauthenticated", message: "Sign-in is required" },
      { status: 401 },
    );
  }

  const { path } = await context.params;
  const upstreamUrl = new URL(`${API_BASE_URL}/api/${path.join("/")}`);
  upstreamUrl.search = req.nextUrl.search;

  const hasBody = !["GET", "HEAD"].includes(req.method);
  const upstream = await fetch(upstreamUrl, {
    method: req.method,
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      ...(hasBody
        ? {
            "content-type":
              req.headers.get("content-type") ?? "application/json",
          }
        : {}),
    },
    body: hasBody ? await req.text() : undefined,
    cache: "no-store",
  });

  const body = await upstream.text();
  return new NextResponse(body, {
    status: upstream.status,
    headers: {
      "content-type":
        upstream.headers.get("content-type") ?? "application/json",
    },
  });
}

export {
  handle as GET,
  handle as POST,
  handle as PUT,
  handle as DELETE,
  handle as PATCH,
};
