import { describe, expect, it } from "vitest";

import { currentSubPath } from "./ProjectSwitcher";

describe("currentSubPath", () => {
  it("returns the trailing path after the current project segment", () => {
    expect(
      currentSubPath("/app/ws-1/projects/proj-1/overview", "ws-1", "proj-1"),
    ).toBe("/overview");
  });

  it("returns an empty string on the project root", () => {
    expect(currentSubPath("/app/ws-1/projects/proj-1", "ws-1", "proj-1")).toBe(
      "",
    );
  });

  it("returns an empty string when there is no current project", () => {
    expect(currentSubPath("/app/ws-1", "ws-1", undefined)).toBe("");
  });

  it("returns an empty string when the pathname does not match the current project", () => {
    expect(
      currentSubPath(
        "/app/ws-1/projects/other-project/overview",
        "ws-1",
        "proj-1",
      ),
    ).toBe("");
  });
});
