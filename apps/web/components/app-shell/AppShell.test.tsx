import { describe, expect, it } from "vitest";

import { parseRoute } from "./AppShell";

describe("parseRoute", () => {
  it("extracts workspaceId and projectId from a project-scoped path", () => {
    expect(parseRoute("/app/ws-123/projects/proj-456")).toEqual({
      workspaceId: "ws-123",
      projectId: "proj-456",
    });
  });

  it("extracts only workspaceId from a workspace-scoped path", () => {
    expect(parseRoute("/app/ws-123")).toEqual({
      workspaceId: "ws-123",
      projectId: undefined,
    });
  });

  it("does not treat the reserved /app/workspaces/new route as a workspace-scoped path", () => {
    // Regression: this literal path shape matches /app/{workspaceId} and previously caused the
    // sidebar to fetch and briefly render project data for a fake "workspaces" workspace.
    expect(parseRoute("/app/workspaces/new")).toEqual({
      workspaceId: undefined,
      projectId: undefined,
    });
  });

  it("does not treat the reserved .../projects/new route as project-scoped", () => {
    // Regression: this previously made "new" look like a real projectId, firing lookups for a
    // project that doesn't exist while still correctly resolving the real workspaceId.
    expect(parseRoute("/app/ws-123/projects/new")).toEqual({
      workspaceId: "ws-123",
      projectId: undefined,
    });
  });

  it("returns nothing for routes outside the authenticated app shell", () => {
    expect(parseRoute("/auth/sign-in")).toEqual({});
  });

  it("does not confuse a real workspace whose id happens to start with 'workspaces'", () => {
    // Real workspace ids are backend-generated UUIDs, but this guards the lookahead's precision:
    // it must only exclude the exact literal segment "workspaces", not any id with that prefix.
    expect(parseRoute("/app/workspaces-2/projects/proj-1")).toEqual({
      workspaceId: "workspaces-2",
      projectId: "proj-1",
    });
  });
});
