import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SkeletonBlock } from "./Skeleton";

describe("SkeletonBlock", () => {
  it("exposes an accessible status role and label for screen readers", () => {
    render(<SkeletonBlock label="Loading project status" lines={3} />);

    const status = screen.getByRole("status", {
      name: "Loading project status",
    });
    expect(status).toBeInTheDocument();
  });
});
