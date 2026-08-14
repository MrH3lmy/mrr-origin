import { describe, expect, it } from "vitest";

import { isSafeRedirectPath } from "./safe-redirect";

const REQUEST_URL = "https://app.mrrorigin.example/auth/session";

describe("isSafeRedirectPath", () => {
  it("accepts a same-origin application path", () => {
    expect(isSafeRedirectPath("/app/ws-1/projects/proj-1", REQUEST_URL)).toBe(
      true,
    );
  });

  it("rejects a protocol-relative URL (the open-redirect case)", () => {
    // new URL("//evil.example", "https://app.mrrorigin.example/...") resolves to
    // https://evil.example -- this is exactly the primitive that must be rejected.
    expect(isSafeRedirectPath("//evil.example", REQUEST_URL)).toBe(false);
  });

  it("rejects a backslash-prefixed variant some browsers treat as protocol-relative", () => {
    expect(isSafeRedirectPath("/\\evil.example", REQUEST_URL)).toBe(false);
  });

  it("rejects an absolute URL to a different origin", () => {
    expect(isSafeRedirectPath("https://evil.example/app", REQUEST_URL)).toBe(
      false,
    );
  });

  it("rejects a value that doesn't start with a slash", () => {
    expect(isSafeRedirectPath("app/ws-1", REQUEST_URL)).toBe(false);
    expect(isSafeRedirectPath("evil.example", REQUEST_URL)).toBe(false);
  });

  it("accepts the root app path", () => {
    expect(isSafeRedirectPath("/app", REQUEST_URL)).toBe(true);
  });
});
