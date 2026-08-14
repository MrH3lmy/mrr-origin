import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { buildClient } from "./client";
import { ApiError } from "./errors";

describe("buildClient", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it("parses a successful JSON response", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ id: "1" }), { status: 200 }),
    );
    const client = buildClient("/api", () => ({}));

    const result = await client.get<{ id: string }>("/workspaces");

    expect(result).toEqual({ id: "1" });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("throws an ApiError with the backend code/message on failure", async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(
          JSON.stringify({ code: "invalid_key", message: "Wrong key" }),
          {
            status: 401,
          },
        ),
    );
    const client = buildClient("/api", () => ({}));

    await expect(client.get("/workspaces")).rejects.toMatchObject({
      status: 401,
      code: "invalid_key",
      message: "Wrong key",
    });
    await expect(client.get("/workspaces")).rejects.toBeInstanceOf(ApiError);
  });

  it("falls back to a generic error when the failure body has no code/message", async () => {
    fetchMock.mockResolvedValue(new Response("", { status: 500 }));
    const client = buildClient("/api", () => ({}));

    await expect(client.get("/workspaces")).rejects.toMatchObject({
      status: 500,
      code: "unknown_error",
    });
  });

  it("attaches extra headers (e.g. Authorization) to every request", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify([]), { status: 200 }),
    );
    const client = buildClient("/api", () => ({
      Authorization: "Bearer token",
    }));

    await client.post("/workspaces", { name: "Acme" });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workspaces",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ Authorization: "Bearer token" }),
        body: JSON.stringify({ name: "Acme" }),
      }),
    );
  });

  it("handles an empty 204 response for delete", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const client = buildClient("/api", () => ({}));

    await expect(
      client.del("/workspaces/1/allowed-domains/2"),
    ).resolves.toBeUndefined();
  });
});
