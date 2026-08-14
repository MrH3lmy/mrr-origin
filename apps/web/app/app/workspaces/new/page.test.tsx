import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";

import NewWorkspacePage from "./page";

const { createWorkspace } = vi.hoisted(() => ({ createWorkspace: vi.fn() }));
const { push } = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("@/lib/api/workspaces", () => ({ createWorkspace }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

describe("NewWorkspacePage", () => {
  beforeEach(() => {
    createWorkspace.mockReset();
    push.mockReset();
  });

  it("derives a slug from the workspace name until the slug is edited directly", async () => {
    const user = userEvent.setup();
    render(<NewWorkspacePage />);

    await user.type(screen.getByLabelText(/workspace name/i), "Acme Rocket Co");

    expect(screen.getByLabelText(/slug/i)).toHaveValue("acme-rocket-co");
  });

  it("creates the workspace and navigates to it", async () => {
    const user = userEvent.setup();
    createWorkspace.mockResolvedValue({
      id: "ws-1",
      name: "Acme",
      slug: "acme",
      reportingCurrency: "USD",
      role: "OWNER",
      createdAt: "2026-08-14T00:00:00Z",
    });

    render(<NewWorkspacePage />);
    await user.type(screen.getByLabelText(/workspace name/i), "Acme");
    await user.click(screen.getByRole("button", { name: /create workspace/i }));

    await waitFor(() =>
      expect(createWorkspace).toHaveBeenCalledWith(
        {},
        { name: "Acme", slug: "acme" },
      ),
    );
    await waitFor(() => expect(push).toHaveBeenCalledWith("/app/ws-1"));
  });

  it("shows a retry-safe error and re-enables the form on failure", async () => {
    const user = userEvent.setup();
    createWorkspace.mockRejectedValue(
      new ApiError(409, "slug_conflict", "Workspace slug is already in use"),
    );

    render(<NewWorkspacePage />);
    await user.type(screen.getByLabelText(/workspace name/i), "Acme");
    await user.click(screen.getByRole("button", { name: /create workspace/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Workspace slug is already in use",
    );
    expect(
      screen.getByRole("button", { name: /create workspace/i }),
    ).toBeEnabled();
    expect(push).not.toHaveBeenCalled();
  });

  it("disables submit until required fields are filled", () => {
    render(<NewWorkspacePage />);

    expect(
      screen.getByRole("button", { name: /create workspace/i }),
    ).toBeDisabled();
  });
});
