import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import {
  getWeeklySummaryOptOut,
  setWeeklySummaryOptOut,
} from "@/lib/api/notifications";

import { WeeklySummaryOptOutToggle } from "./WeeklySummaryOptOutToggle";

vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));
vi.mock("@/lib/api/notifications", () => ({
  getWeeklySummaryOptOut: vi.fn(),
  setWeeklySummaryOptOut: vi.fn(),
}));

const mockGet = vi.mocked(getWeeklySummaryOptOut);
const mockSet = vi.mocked(setWeeklySummaryOptOut);

describe("WeeklySummaryOptOutToggle", () => {
  it("shows the subscribed state and unsubscribes on click", async () => {
    mockGet.mockResolvedValue({ optedOut: false });
    mockSet.mockResolvedValue({ optedOut: true });

    render(<WeeklySummaryOptOutToggle workspaceId="ws-1" projectId="proj-1" />);

    expect(
      await screen.findByText(/you are receiving the weekly summary/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /unsubscribe/i }));

    await waitFor(() =>
      expect(
        screen.getByText(/you are not receiving the weekly summary/i),
      ).toBeInTheDocument(),
    );
    expect(mockSet).toHaveBeenCalledWith(
      expect.anything(),
      "ws-1",
      "proj-1",
      true,
    );
  });

  it("shows the opted-out state and resumes on click", async () => {
    mockGet.mockResolvedValue({ optedOut: true });
    mockSet.mockResolvedValue({ optedOut: false });

    render(<WeeklySummaryOptOutToggle workspaceId="ws-1" projectId="proj-1" />);

    expect(
      await screen.findByText(/you are not receiving the weekly summary/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /resume email/i }));

    await waitFor(() =>
      expect(
        screen.getByText(/you are receiving the weekly summary/i),
      ).toBeInTheDocument(),
    );
  });

  it("shows an error when the initial load fails", async () => {
    mockGet.mockRejectedValue(new Error("network down"));

    render(<WeeklySummaryOptOutToggle workspaceId="ws-1" projectId="proj-1" />);

    expect(
      await screen.findByText(/could not load your subscription status/i),
    ).toBeInTheDocument();
  });
});
