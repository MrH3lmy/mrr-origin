import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/errors";
import type {
  ProjectDiagnosticsReport,
  VerificationAttempt,
} from "@/lib/api/types";

import { VerificationCard } from "./VerificationCard";

const { getDiagnostics, getVerificationStatus, startVerification } = vi.hoisted(
  () => ({
    getDiagnostics: vi.fn(),
    getVerificationStatus: vi.fn(),
    startVerification: vi.fn(),
  }),
);

vi.mock("@/lib/api/tracking", () => ({
  getDiagnostics,
  getVerificationStatus,
  startVerification,
}));
vi.mock("@/lib/api/client", () => ({ createBrowserClient: () => ({}) }));

function diagnostics(
  state: ProjectDiagnosticsReport["state"],
): ProjectDiagnosticsReport {
  return {
    workspaceId: "ws-1",
    projectId: "proj-1",
    state,
    everReceivedTraffic: state === "RECEIVING",
    lastAcceptedEventAt: null,
    lastAcceptedEventType: null,
    blockedOrigin: {
      count: state === "BLOCKED_ORIGIN" ? 2 : 0,
      lastOccurredAt: null,
    },
    invalidKey: {
      count: state === "INVALID_KEY" ? 1 : 0,
      lastOccurredAt: null,
    },
    invalidPayload: {
      count: state === "INVALID_PAYLOAD" ? 1 : 0,
      lastOccurredAt: null,
    },
    identityCoverage: { totalVisitors: 0, identifiedVisitors: 0 },
    computedAt: "2026-08-14T00:00:00Z",
  };
}

const pendingAttempt: VerificationAttempt = {
  id: "attempt-1",
  token: "token-1",
  status: "PENDING",
  createdAt: "2026-08-14T00:00:00Z",
  expiresAt: "2026-08-14T00:15:00Z",
  succeededAt: null,
};

const baseProps = {
  workspaceId: "ws-1",
  projectId: "proj-1",
  hasActiveKey: true,
  onDiagnosticsChange: vi.fn(),
  onVerificationChange: vi.fn(),
};

describe("VerificationCard", () => {
  beforeEach(() => {
    getDiagnostics.mockReset();
    getVerificationStatus.mockReset();
    startVerification.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows plain-language guidance without a key installed", () => {
    render(
      <VerificationCard
        {...baseProps}
        hasActiveKey={false}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={null}
      />,
    );

    expect(
      screen.getByText(/generate an installation key first/i),
    ).toBeInTheDocument();
  });

  it("renders NO_TRAFFIC with waiting guidance", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={null}
      />,
    );

    expect(screen.getByText(/no traffic detected yet/i)).toBeInTheDocument();
    expect(screen.getByText("NO_TRAFFIC")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /start verification/i }),
    ).toBeInTheDocument();
  });

  it("renders BLOCKED_ORIGIN with a domain-recovery hint and the attempt count", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("BLOCKED_ORIGIN")}
        verification={pendingAttempt}
      />,
    );

    expect(
      screen.getByText(/domain that isn't allowed yet/i),
    ).toBeInTheDocument();
    expect(screen.getByText("BLOCKED_ORIGIN")).toBeInTheDocument();
  });

  it("renders INVALID_KEY without exposing another project's secret", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("INVALID_KEY")}
        verification={pendingAttempt}
      />,
    );

    expect(
      screen.getByText(/wrong installation key is being used/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/mrr_/)).not.toBeInTheDocument();
  });

  it("renders INVALID_PAYLOAD as a danger-toned implementation error", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("INVALID_PAYLOAD")}
        verification={pendingAttempt}
      />,
    );

    expect(screen.getByText(/couldn't be processed/i)).toBeInTheDocument();
  });

  it("renders RECEIVING as a clear success confirmation", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("RECEIVING")}
        verification={{
          ...pendingAttempt,
          status: "SUCCEEDED",
          succeededAt: "2026-08-14T00:05:00Z",
        }}
      />,
    );

    expect(screen.getByText(/tracker detected/i)).toBeInTheDocument();
    expect(screen.getByText(/continue to stripe/i)).toBeInTheDocument();
  });

  it("shows an expired-attempt recovery action when the attempt lapsed", () => {
    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={{ ...pendingAttempt, status: "EXPIRED" }}
      />,
    );

    expect(screen.getByText(/verification check expired/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /start a new check/i }),
    ).toBeInTheDocument();
  });

  it("starts verification and reports the new attempt/diagnostics to the parent", async () => {
    const user = userEvent.setup();
    startVerification.mockResolvedValue(pendingAttempt);
    getDiagnostics.mockResolvedValue(diagnostics("NO_TRAFFIC"));
    getVerificationStatus.mockResolvedValue(pendingAttempt);
    const onVerificationChange = vi.fn();
    const onDiagnosticsChange = vi.fn();

    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={null}
        onVerificationChange={onVerificationChange}
        onDiagnosticsChange={onDiagnosticsChange}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /start verification/i }),
    );

    await waitFor(() =>
      expect(startVerification).toHaveBeenCalledWith({}, "ws-1", "proj-1"),
    );
    await waitFor(() =>
      expect(onVerificationChange).toHaveBeenCalledWith(pendingAttempt),
    );
    await waitFor(() =>
      expect(onDiagnosticsChange).toHaveBeenCalledWith(
        diagnostics("NO_TRAFFIC"),
      ),
    );
  });

  it("shows a retry-safe error message when checking again fails", async () => {
    const user = userEvent.setup();
    getDiagnostics.mockRejectedValue(
      new ApiError(500, "server_error", "Backend unavailable"),
    );
    getVerificationStatus.mockResolvedValue(pendingAttempt);

    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={pendingAttempt}
      />,
    );

    await user.click(screen.getByRole("button", { name: /check again/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Backend unavailable",
    );
    // The button remains present and enabled, so retrying is safe.
    expect(screen.getByRole("button", { name: /check again/i })).toBeEnabled();
  });

  it("treats a 404 verification-status response as 'not started' rather than an error", async () => {
    const user = userEvent.setup();
    getDiagnostics.mockResolvedValue(diagnostics("NO_TRAFFIC"));
    getVerificationStatus.mockRejectedValue(
      new ApiError(404, "not_found", "No attempt"),
    );
    const onVerificationChange = vi.fn();

    render(
      <VerificationCard
        {...baseProps}
        diagnostics={diagnostics("NO_TRAFFIC")}
        verification={pendingAttempt}
        onVerificationChange={onVerificationChange}
      />,
    );

    await user.click(screen.getByRole("button", { name: /check again/i }));

    await waitFor(() =>
      expect(onVerificationChange).toHaveBeenCalledWith(null),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
