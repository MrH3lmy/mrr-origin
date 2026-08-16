import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CustomerDirectoryEntry } from "@/lib/api/types";

import { CustomersClient } from "./CustomersClient";

const { listCustomers } = vi.hoisted(() => ({ listCustomers: vi.fn() }));

vi.mock("@/lib/api/customers", () => ({ listCustomers }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const baseProps = { workspaceId: "ws-1", projectId: "proj-1" };

function entry(
  overrides: Partial<CustomerDirectoryEntry> = {},
): CustomerDirectoryEntry {
  return {
    stripeCustomerId: "cus_1",
    deleted: false,
    providerCreatedAt: "2026-01-01T00:00:00Z",
    acquisitionEffectiveAt: "2026-01-05T00:00:00Z",
    confidence: "STRONG",
    unattributedReason: null,
    firstSource: "google",
    currentMrr: [{ currency: "USD", amountMinor: 1500 }],
    subscriptionStatuses: ["active"],
    ...overrides,
  };
}

describe("CustomersClient", () => {
  beforeEach(() => {
    listCustomers.mockReset();
  });

  it("shows a loading skeleton, then the customer list", async () => {
    listCustomers.mockResolvedValue({ entries: [entry()], nextCursor: null });

    render(<CustomersClient {...baseProps} />);

    expect(screen.getByLabelText("Loading customers")).toBeInTheDocument();
    expect(await screen.findByText("cus_1")).toBeInTheDocument();
    expect(screen.getByText("google")).toBeInTheDocument();
    expect(screen.getByText("$15")).toBeInTheDocument();
  });

  it("renders an empty state with no customers", async () => {
    listCustomers.mockResolvedValue({ entries: [], nextCursor: null });

    render(<CustomersClient {...baseProps} />);

    expect(await screen.findByText("No customers yet")).toBeInTheDocument();
  });

  it("renders an error state and lets the user see what failed", async () => {
    listCustomers.mockRejectedValue(new Error("network down"));

    render(<CustomersClient {...baseProps} />);

    expect(
      await screen.findByText("Could not load customers"),
    ).toBeInTheDocument();
  });

  it("humanizes the unattributed reason while keeping it available for inspection", async () => {
    listCustomers.mockResolvedValue({
      entries: [
        entry({
          confidence: "UNATTRIBUTED",
          unattributedReason: "NO_ACTIVE_LINK",
          firstSource: null,
        }),
      ],
      nextCursor: null,
    });

    render(<CustomersClient {...baseProps} />);

    expect(
      await screen.findByText(
        "No application user is linked to this Stripe customer.",
      ),
    ).toBeInTheDocument();
  });

  it("searches by customer ID and refetches", async () => {
    const user = userEvent.setup();
    listCustomers.mockResolvedValueOnce({
      entries: [entry()],
      nextCursor: null,
    });
    listCustomers.mockResolvedValueOnce({
      entries: [entry({ stripeCustomerId: "cus_match" })],
      nextCursor: null,
    });

    render(<CustomersClient {...baseProps} />);
    await screen.findByText("cus_1");

    await user.type(
      screen.getByLabelText("Search by Stripe customer ID"),
      "match",
    );
    await user.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("cus_match")).toBeInTheDocument();
    expect(listCustomers).toHaveBeenLastCalledWith({}, "ws-1", "proj-1", {
      search: "match",
    });
  });

  it("loads the next page and appends entries", async () => {
    const user = userEvent.setup();
    listCustomers.mockResolvedValueOnce({
      entries: [entry()],
      nextCursor: "cursor-1",
    });
    listCustomers.mockResolvedValueOnce({
      entries: [entry({ stripeCustomerId: "cus_2" })],
      nextCursor: null,
    });

    render(<CustomersClient {...baseProps} />);
    await screen.findByText("cus_1");

    await user.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("cus_2")).toBeInTheDocument();
    expect(screen.getByText("cus_1")).toBeInTheDocument();
  });
});
