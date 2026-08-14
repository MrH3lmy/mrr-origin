import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type {
  ActiveIngestionKey,
  AllowedDomain,
  Project,
} from "@/lib/api/types";

import { TrackerInstallCard } from "./TrackerInstallCard";

const {
  getActiveIngestionKey,
  issueOrRotateIngestionKey,
  listAllowedDomains,
  addAllowedDomain,
  removeAllowedDomain,
} = vi.hoisted(() => ({
  getActiveIngestionKey: vi.fn(),
  issueOrRotateIngestionKey: vi.fn(),
  listAllowedDomains: vi.fn(),
  addAllowedDomain: vi.fn(),
  removeAllowedDomain: vi.fn(),
}));

vi.mock("@/lib/api/tracking", () => ({
  getActiveIngestionKey,
  issueOrRotateIngestionKey,
  listAllowedDomains,
  addAllowedDomain,
  removeAllowedDomain,
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const project: Project = {
  id: "proj-1",
  workspaceId: "ws-1",
  name: "Production",
  domain: "app.example.com",
  publicKey: "pk_x",
  timezone: "UTC",
  createdAt: "2026-08-14T00:00:00Z",
};

const noKey: ActiveIngestionKey = {
  present: false,
  id: null,
  prefix: null,
  createdAt: null,
};
const activeKey: ActiveIngestionKey = {
  present: true,
  id: "key-1",
  prefix: "mrr_abc123",
  createdAt: "2026-08-14T00:00:00Z",
};
const domains: AllowedDomain[] = [{ id: "dom-1", domain: "app.example.com" }];

describe("TrackerInstallCard", () => {
  beforeEach(() => {
    getActiveIngestionKey.mockReset();
    issueOrRotateIngestionKey.mockReset();
    listAllowedDomains.mockReset();
    addAllowedDomain.mockReset();
    removeAllowedDomain.mockReset();
  });

  it("prompts to generate a key when none exists, and never shows a secret placeholder as real", () => {
    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={noKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={[]}
      />,
    );

    expect(
      screen.getByRole("button", { name: /generate installation key/i }),
    ).toBeInTheDocument();
    expect(screen.getByText("Not installed")).toBeInTheDocument();
    expect(screen.getByText(/generate one below/i)).toBeInTheDocument();
  });

  it("shows the secret exactly once immediately after issuing a key", async () => {
    const user = userEvent.setup();
    const onActiveKeyChange = vi.fn();
    issueOrRotateIngestionKey.mockResolvedValue({
      id: "key-1",
      secret: "mrr_abc123_secretvalue",
      prefix: "mrr_abc123",
      rotated: false,
    });
    getActiveIngestionKey.mockResolvedValue(activeKey);

    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={noKey}
        onActiveKeyChange={onActiveKeyChange}
        initialDomains={[]}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /generate installation key/i }),
    );

    expect(await screen.findByText(/copy this key now/i)).toBeInTheDocument();
    expect(
      screen.getAllByText(/mrr_abc123_secretvalue/).length,
    ).toBeGreaterThan(0);
    await waitFor(() =>
      expect(onActiveKeyChange).toHaveBeenCalledWith(activeKey),
    );
  });

  it("never displays the raw secret again once a key is already active", () => {
    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={[]}
      />,
    );

    expect(
      screen.getByText(/active key prefix: mrr_abc123/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/copy this key now/i)).not.toBeInTheDocument();
    expect(
      screen.getByText(/your ingestion key — generate one below/i),
    ).toBeInTheDocument();
  });

  it("requires an explicit confirm step before rotating an existing key", async () => {
    const user = userEvent.setup();
    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={[]}
      />,
    );

    await user.click(screen.getByRole("button", { name: /rotate key/i }));

    expect(
      screen.getByText(/immediately invalidates the current key/i),
    ).toBeInTheDocument();
    expect(issueOrRotateIngestionKey).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: /confirm rotate/i }));
    await waitFor(() =>
      expect(issueOrRotateIngestionKey).toHaveBeenCalledWith(
        {},
        "ws-1",
        "proj-1",
      ),
    );
  });

  it("warns when no allowed domains are configured yet", () => {
    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={[]}
      />,
    );

    expect(screen.getByText(/no allowed domains yet/i)).toBeInTheDocument();
  });

  it("adds a domain and refreshes the list", async () => {
    const user = userEvent.setup();
    addAllowedDomain.mockResolvedValue(domains[0]);
    listAllowedDomains.mockResolvedValue(domains);

    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={[]}
      />,
    );

    await user.type(
      screen.getByLabelText(/domain to allow/i),
      "app.example.com",
    );
    await user.click(screen.getByRole("button", { name: /add domain/i }));

    await waitFor(() =>
      expect(addAllowedDomain).toHaveBeenCalledWith(
        {},
        "ws-1",
        "proj-1",
        "app.example.com",
      ),
    );
    expect(await screen.findByText("app.example.com")).toBeInTheDocument();
  });

  it("shows a retry-safe error when removing a domain fails", async () => {
    const user = userEvent.setup();
    removeAllowedDomain.mockRejectedValue(
      new ApiError(500, "server_error", "Could not remove domain"),
    );

    render(
      <TrackerInstallCard
        workspaceId="ws-1"
        projectId="proj-1"
        project={project}
        activeKey={activeKey}
        onActiveKeyChange={vi.fn()}
        initialDomains={domains}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /remove app\.example\.com/i }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not remove domain",
    );
    // The domain stays listed since the removal failed -- no optimistic state.
    expect(screen.getByText("app.example.com")).toBeInTheDocument();
  });
});
