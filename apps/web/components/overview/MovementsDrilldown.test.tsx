import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type { MrrMovementEntry } from "@/lib/api/types";

import { MovementsDrilldown } from "./MovementsDrilldown";

const { listMrrMovements } = vi.hoisted(() => ({ listMrrMovements: vi.fn() }));

vi.mock("@/lib/api/reporting", () => ({ listMrrMovements }));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

const entry: MrrMovementEntry = {
  movementId: "mv-1",
  stripeCustomerId: "cus_1",
  currency: "USD",
  amountMinor: 2000,
  movementType: "NEW",
  effectiveAt: "2026-04-05T00:00:00Z",
  confidence: "STRONG",
  unattributedReason: null,
  firstTouch: { source: "google", campaign: null, landingPage: null },
  lastTouch: { source: "google", campaign: null, landingPage: null },
};

const unattributedEntry: MrrMovementEntry = {
  ...entry,
  movementId: "mv-2",
  stripeCustomerId: "cus_2",
  confidence: "UNATTRIBUTED",
  unattributedReason: "NO_ACTIVE_LINK",
  firstTouch: null,
  lastTouch: null,
};

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  from: "2026-04-01T00:00:00Z",
  to: "2026-05-01T00:00:00Z",
  movementType: null,
  source: null,
  currency: null,
  onClearFilters: vi.fn(),
};

describe("MovementsDrilldown", () => {
  beforeEach(() => {
    listMrrMovements.mockReset();
  });

  it("shows a loading state, then the fetched movements with their evidence", async () => {
    listMrrMovements.mockResolvedValue({ entries: [entry], nextCursor: null });

    render(<MovementsDrilldown {...baseProps} />);

    expect(screen.getByLabelText(/loading movements/i)).toBeInTheDocument();

    expect(await screen.findByText("cus_1")).toBeInTheDocument();
    expect(screen.getByText("google")).toBeInTheDocument();
  });

  it("shows the humanized unattributed reason for unattributed movements", async () => {
    listMrrMovements.mockResolvedValue({
      entries: [unattributedEntry],
      nextCursor: null,
    });

    render(<MovementsDrilldown {...baseProps} />);

    expect(
      await screen.findByText(
        "No application user is linked to this Stripe customer.",
      ),
    ).toBeInTheDocument();
  });

  it("shows an empty state with no movements", async () => {
    listMrrMovements.mockResolvedValue({ entries: [], nextCursor: null });

    render(<MovementsDrilldown {...baseProps} />);

    expect(
      await screen.findByText(/no movements match this view/i),
    ).toBeInTheDocument();
  });

  it("shows a retry-safe error message when loading fails", async () => {
    listMrrMovements.mockRejectedValue(
      new ApiError(500, "server_error", "Backend unavailable"),
    );

    render(<MovementsDrilldown {...baseProps} />);

    expect(await screen.findByText("Backend unavailable")).toBeInTheDocument();
  });

  it("loads more movements via keyset pagination and appends them", async () => {
    const user = userEvent.setup();
    listMrrMovements
      .mockResolvedValueOnce({ entries: [entry], nextCursor: "cursor-1" })
      .mockResolvedValueOnce({
        entries: [unattributedEntry],
        nextCursor: null,
      });

    render(<MovementsDrilldown {...baseProps} />);

    expect(await screen.findByText("cus_1")).toBeInTheDocument();
    const loadMore = screen.getByRole("button", { name: /load more/i });

    await user.click(loadMore);

    await waitFor(() => expect(screen.getByText("cus_2")).toBeInTheDocument());
    expect(listMrrMovements).toHaveBeenLastCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({ cursor: "cursor-1" }),
    );
  });

  it("shows active filter chips and clears them on request", async () => {
    const user = userEvent.setup();
    listMrrMovements.mockResolvedValue({ entries: [entry], nextCursor: null });
    const onClearFilters = vi.fn();

    render(
      <MovementsDrilldown
        {...baseProps}
        movementType="NEW"
        source="google"
        onClearFilters={onClearFilters}
      />,
    );

    await screen.findByText("cus_1");
    // "New MRR" and "google" each appear twice: once as the active filter chip, once in the row
    // (movement-type column and attributed-source badge, respectively).
    expect(screen.getAllByText("New MRR").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("google").length).toBeGreaterThanOrEqual(2);

    await user.click(screen.getByRole("button", { name: /clear filters/i }));
    expect(onClearFilters).toHaveBeenCalled();
  });

  it("passes the selected currency to the API call and shows it as a filter chip", async () => {
    listMrrMovements.mockResolvedValue({ entries: [entry], nextCursor: null });

    render(
      <MovementsDrilldown {...baseProps} movementType="NEW" currency="USD" />,
    );

    await screen.findByText("cus_1");

    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({ movementType: "NEW", currency: "USD" }),
    );
    expect(screen.getByText("USD")).toBeInTheDocument();
  });

  it("passes campaignMissing and landingPage filters through to the API and shows them as chips", async () => {
    listMrrMovements.mockResolvedValue({ entries: [entry], nextCursor: null });

    render(
      <MovementsDrilldown
        {...baseProps}
        source="google"
        campaignMissing
        landingPage="https://example.test/a"
      />,
    );

    await screen.findByText("cus_1");

    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({
        source: "google",
        campaignMissing: true,
        landingPage: "https://example.test/a",
      }),
    );
    expect(screen.getByText("No campaign")).toBeInTheDocument();
    expect(screen.getByText("https://example.test/a")).toBeInTheDocument();
  });

  it("distinguishes a real campaign literally named 'NONE' from the no-campaign bucket", async () => {
    // Regression: campaign filtering used to encode "no campaign captured" as the sentinel string
    // "NONE" inside the campaign value itself, colliding with any real UTM campaign named "NONE".
    listMrrMovements.mockResolvedValue({ entries: [entry], nextCursor: null });

    render(
      <MovementsDrilldown {...baseProps} source="google" campaign="NONE" />,
    );

    await screen.findByText("cus_1");

    expect(listMrrMovements).toHaveBeenCalledWith(
      {},
      "ws-1",
      "proj-1",
      baseProps.from,
      baseProps.to,
      expect.objectContaining({
        source: "google",
        campaign: "NONE",
        campaignMissing: undefined,
      }),
    );
    // The filter chip shows the literal campaign name, not "No campaign".
    expect(screen.getByText("NONE")).toBeInTheDocument();
    expect(screen.queryByText("No campaign")).not.toBeInTheDocument();
  });
});
