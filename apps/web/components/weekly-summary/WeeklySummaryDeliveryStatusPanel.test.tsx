import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import {
  listWeeklySummaryDeliveries,
  replayWeeklySummaryDelivery,
  sendWeeklySummaryNow,
} from "@/lib/api/notifications";
import type { WeeklySummaryDelivery } from "@/lib/api/types";

import { WeeklySummaryDeliveryStatusPanel } from "./WeeklySummaryDeliveryStatusPanel";

vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));
vi.mock("@/lib/api/notifications", () => ({
  listWeeklySummaryDeliveries: vi.fn(),
  replayWeeklySummaryDelivery: vi.fn(),
  sendWeeklySummaryNow: vi.fn(),
}));

const mockList = vi.mocked(listWeeklySummaryDeliveries);
const mockReplay = vi.mocked(replayWeeklySummaryDelivery);
const mockSend = vi.mocked(sendWeeklySummaryNow);

const blockedDelivery: WeeklySummaryDelivery = {
  id: "delivery-1",
  recipientEmail: null,
  weekStart: "2026-03-02",
  status: "BLOCKED_MISSING_EMAIL",
  attemptCount: 0,
  lastError: null,
  lastOutcomeAmbiguous: false,
  providerMessageId: null,
  createdAt: "2026-03-09T09:00:00Z",
  updatedAt: "2026-03-09T09:00:00Z",
};

describe("WeeklySummaryDeliveryStatusPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders nothing for a non-manager (403)", async () => {
    mockList.mockRejectedValue(new ApiError(403, "FORBIDDEN", "nope"));

    const { container } = render(
      <WeeklySummaryDeliveryStatusPanel
        workspaceId="ws-1"
        projectId="proj-1"
      />,
    );

    await waitFor(() => expect(mockList).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("shows a BLOCKED_MISSING_EMAIL row with a Replay action and replays it", async () => {
    mockList.mockResolvedValue([blockedDelivery]);
    mockReplay.mockResolvedValue({ triggeredAt: "2026-03-09T09:05:00Z" });

    render(
      <WeeklySummaryDeliveryStatusPanel
        workspaceId="ws-1"
        projectId="proj-1"
      />,
    );

    expect(
      await screen.findByText(/no verified email yet/i),
    ).toBeInTheDocument();
    expect(screen.getByText("No verified email")).toBeInTheDocument(); // status badge (exact, distinct from the recipient text)

    fireEvent.click(screen.getByRole("button", { name: /replay/i }));

    await waitFor(() =>
      expect(mockReplay).toHaveBeenCalledWith(
        expect.anything(),
        "ws-1",
        "proj-1",
        "delivery-1",
      ),
    );
    // Replay triggers a reload of the list.
    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(2));
  });

  it("shows an error and does not hide the panel when replay is rejected", async () => {
    mockList.mockResolvedValue([blockedDelivery]);
    mockReplay.mockRejectedValue(
      new ApiError(
        409,
        "CONFLICT",
        "This member still has no verified email captured",
      ),
    );

    render(
      <WeeklySummaryDeliveryStatusPanel
        workspaceId="ws-1"
        projectId="proj-1"
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /replay/i }));

    expect(
      await screen.findByText(/no verified email captured/i),
    ).toBeInTheDocument();
  });

  it("triggers a manual send via the Send now button", async () => {
    mockList.mockResolvedValue([]);
    mockSend.mockResolvedValue({ triggeredAt: "2026-03-09T09:05:00Z" });

    render(
      <WeeklySummaryDeliveryStatusPanel
        workspaceId="ws-1"
        projectId="proj-1"
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /send now/i }));

    await waitFor(() =>
      expect(mockSend).toHaveBeenCalledWith(
        expect.anything(),
        "ws-1",
        "proj-1",
      ),
    );
  });
});
