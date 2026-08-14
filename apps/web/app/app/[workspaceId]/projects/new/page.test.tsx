import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";

import NewProjectPage from "./page";

const { createProject } = vi.hoisted(() => ({ createProject: vi.fn() }));
const { push } = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock("@/lib/api/workspaces", () => ({ createProject }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
  useParams: () => ({ workspaceId: "ws-1" }),
}));

describe("NewProjectPage", () => {
  beforeEach(() => {
    createProject.mockReset();
    push.mockReset();
  });

  it("only exposes fields the backend contract supports (name, domain)", () => {
    render(<NewProjectPage />);

    expect(screen.getByLabelText(/project name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/primary domain/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/timezone/i)).not.toBeInTheDocument();
  });

  it("creates the project scoped to the current workspace and navigates to it", async () => {
    const user = userEvent.setup();
    createProject.mockResolvedValue({
      id: "proj-1",
      workspaceId: "ws-1",
      name: "Production",
      domain: "app.example.com",
      publicKey: "pk_x",
      timezone: "UTC",
      createdAt: "2026-08-14T00:00:00Z",
    });

    render(<NewProjectPage />);
    await user.type(screen.getByLabelText(/project name/i), "Production");
    await user.type(
      screen.getByLabelText(/primary domain/i),
      "app.example.com",
    );
    await user.click(screen.getByRole("button", { name: /create project/i }));

    await waitFor(() =>
      expect(createProject).toHaveBeenCalledWith({}, "ws-1", {
        name: "Production",
        domain: "app.example.com",
      }),
    );
    await waitFor(() =>
      expect(push).toHaveBeenCalledWith("/app/ws-1/projects/proj-1"),
    );
  });

  it("shows a retry-safe error on failure", async () => {
    const user = userEvent.setup();
    createProject.mockRejectedValue(
      new ApiError(
        409,
        "domain_conflict",
        "Project domain already exists in this workspace",
      ),
    );

    render(<NewProjectPage />);
    await user.type(screen.getByLabelText(/project name/i), "Production");
    await user.type(
      screen.getByLabelText(/primary domain/i),
      "app.example.com",
    );
    await user.click(screen.getByRole("button", { name: /create project/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "already exists in this workspace",
    );
    expect(push).not.toHaveBeenCalled();
  });
});
