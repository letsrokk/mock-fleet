import { describe, expect, it, vi } from "vitest";
import { loadConfigAndOptionCatalog, refreshConfigAndOptionCatalog } from "./configCatalog";

describe("configuration catalog loading", () => {
  it("loads config and the versioned option catalog from their separate API endpoints", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ mockIds: ["orders"], defaultVersion: "3.13.2" }))
      .mockResolvedValueOnce(jsonResponse({
        wireMockVersion: "3.13.2",
        catalogStatus: "newer_unresearched",
        options: [{ name: "--verbose" }]
      }));

    const result = await loadConfigAndOptionCatalog(fetcher);

    expect(fetcher).toHaveBeenNthCalledWith(1, "/__fleet/api/config");
    expect(fetcher).toHaveBeenNthCalledWith(2, "/__fleet/api/config/options?version=3.13.2");
    expect(result).toEqual({
      config: { mockIds: ["orders"], defaultVersion: "3.13.2" },
      optionCatalog: {
        wireMockVersion: "3.13.2",
        catalogStatus: "newer_unresearched",
        options: [{ name: "--verbose" }]
      }
    });
  });

  it("retains the complete prior state when a catalog refresh fails", async () => {
    const previous = await loadConfigAndOptionCatalog(vi.fn()
      .mockResolvedValueOnce(jsonResponse({ mockIds: ["orders"], defaultVersion: "3.13.2" }))
      .mockResolvedValueOnce(jsonResponse({
        wireMockVersion: "3.13.2",
        catalogStatus: "supported",
        options: [{ name: "--verbose" }]
      })));
    const fetcher = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ mockIds: ["payments"], defaultVersion: "3.13.2" }))
      .mockResolvedValueOnce(errorResponse("Catalog service unavailable"));

    const result = await refreshConfigAndOptionCatalog(previous, fetcher);

    expect(result.ok).toBe(false);
    expect(result.state).toBe(previous);
    if (!result.ok) {
      expect(result.error).toBe("Catalog service unavailable");
    }
  });
});

function jsonResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => ""
  } as Response;
}

function errorResponse(message: string) {
  return {
    ok: false,
    status: 503,
    json: async () => ({}),
    text: async () => message
  } as Response;
}
