import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { setSessionCookie } = vi.hoisted(() => ({ setSessionCookie: vi.fn() }));
vi.mock("@/lib/auth/session", () => ({ setSessionCookie }));

import { POST } from "./route";

function postRequest(body: Record<string, string>): NextRequest {
  const form = new URLSearchParams(body);
  return new NextRequest("https://app.mrrorigin.example/auth/session", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: form.toString(),
  });
}

describe("POST /auth/session", () => {
  beforeEach(() => {
    setSessionCookie.mockReset();
    setSessionCookie.mockResolvedValue(undefined);
  });

  it("redirects to the safe redirectTo destination on the same origin", async () => {
    const response = await POST(
      postRequest({ accessToken: "token", redirectTo: "/app/ws-1" }),
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(
      "https://app.mrrorigin.example/app/ws-1",
    );
  });

  it("rejects a protocol-relative redirectTo and falls back to /app", async () => {
    // Regression: this is the open-redirect primitive -- new URL("//evil.example", requestUrl)
    // would otherwise resolve off-origin.
    const response = await POST(
      postRequest({ accessToken: "token", redirectTo: "//evil.example" }),
    );

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(
      "https://app.mrrorigin.example/app",
    );
  });

  it("rejects an absolute cross-origin redirectTo and falls back to /app", async () => {
    const response = await POST(
      postRequest({
        accessToken: "token",
        redirectTo: "https://evil.example/phish",
      }),
    );

    expect(response.headers.get("location")).toBe(
      "https://app.mrrorigin.example/app",
    );
  });

  it("falls back to /app when redirectTo is absent", async () => {
    const response = await POST(postRequest({ accessToken: "token" }));

    expect(response.headers.get("location")).toBe(
      "https://app.mrrorigin.example/app",
    );
  });

  it("redirects to sign-in with an error when the token is missing", async () => {
    const response = await POST(postRequest({}));

    expect(response.status).toBe(303);
    const location = new URL(response.headers.get("location") ?? "");
    expect(location.pathname).toBe("/auth/sign-in");
    expect(location.searchParams.get("error")).toBe("missing_token");
    expect(setSessionCookie).not.toHaveBeenCalled();
  });
});
