import { describe, expect, it, vi } from "vitest";
import {
  LatestConfigRequest,
  editorTarget,
  loadConfigAndOptionCatalog,
  loadEditorCatalog,
  loadOptionCatalogForVersion,
  refreshConfigAndOptionCatalog
} from "./configCatalog";
import type { ConfigView } from "./apiContracts";

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

  it("keeps an unsaved retained baseline pin when opening a mock", () => {
    expect(editorTarget(configView(), "baseline-pin")).toEqual({
      mockId: "baseline-pin",
      draftWireMockVersion: "3.12.1",
      desiredVersion: "3.12.1"
    });
  });

  it("uses the catalog default for a saved explicit inherit choice and a new mock", () => {
    expect(editorTarget(configView(), "saved-inherit")).toEqual({
      mockId: "saved-inherit",
      draftWireMockVersion: null,
      desiredVersion: "3.13.2"
    });
    expect(editorTarget(configView(), "new-mock")).toEqual({
      mockId: "new-mock",
      draftWireMockVersion: null,
      desiredVersion: "3.13.2"
    });
  });

  it("rejects every response older than the latest editor transition", () => {
    const requests = new LatestConfigRequest();
    const initialLoad = requests.begin();
    const refresh = requests.begin();
    const select = requests.begin();

    expect(requests.isCurrent(initialLoad)).toBe(false);
    expect(requests.isCurrent(refresh)).toBe(false);
    expect(requests.isCurrent(select)).toBe(true);
    requests.invalidate();
    expect(requests.isCurrent(select)).toBe(false);
  });

  it("rejects a catalog response for a different version", async () => {
    await expect(loadOptionCatalogForVersion("3.12.1", vi.fn().mockResolvedValue(jsonResponse({
      wireMockVersion: "3.13.2",
      catalogStatus: "supported",
      options: []
    })))).rejects.toThrow("expected 3.12.1");
  });

  it.each([
    ["initial retained selection", "baseline-pin", undefined, "3.12.1"],
    ["refresh preserving an edited pin", "saved-inherit", "3.12.1", "3.12.1"],
    ["select saved inherit", "saved-inherit", undefined, "3.13.2"],
    ["add a new mock", "new-mock", undefined, "3.13.2"],
    ["delete to the retained selection", "baseline-pin", undefined, "3.12.1"],
    ["reset saved inherit", "saved-inherit", undefined, "3.13.2"],
    ["change the desired version", "saved-inherit", "3.12.1", "3.12.1"]
  ])("loads the desired catalog for %s", async (_event, mockId, draftVersion, expectedVersion) => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse({
      wireMockVersion: expectedVersion,
      catalogStatus: "supported",
      options: []
    }));

    const result = await loadEditorCatalog(configView(), mockId, draftVersion, fetcher);

    expect(fetcher).toHaveBeenCalledWith(`/__fleet/api/config/options?version=${expectedVersion}`);
    expect(result.target.desiredVersion).toBe(expectedVersion);
    expect(result.catalog.wireMockVersion).toBe(expectedVersion);
  });
});

function configView(): ConfigView {
  const resources = { requests: {}, limits: {} };
  return {
    resourceVersion: "42",
    mockIds: ["baseline-pin", "saved-inherit", "new-mock"],
    savedMockIds: ["saved-inherit"],
    mocks: [
      {
        mockId: "baseline-pin",
        lifecycle: "STOPPED",
        baseline: { version: "3.12.1", options: [], resources },
        user: { options: [], resources: null },
        effective: { version: "3.12.1", options: [], resources },
        wireMockVersion: "3.12.1",
        runtimeVersion: null
      },
      {
        mockId: "saved-inherit",
        lifecycle: "STOPPED",
        baseline: { version: "3.12.1", options: [], resources },
        user: { version: null, options: [], resources: null },
        effective: { version: "3.13.2", options: [], resources },
        wireMockVersion: "3.13.2",
        runtimeVersion: null
      },
      {
        mockId: "new-mock",
        lifecycle: "STOPPED",
        baseline: { options: [], resources },
        user: { options: [], resources: null },
        effective: { version: "3.13.2", options: [], resources },
        wireMockVersion: "3.13.2",
        runtimeVersion: null
      }
    ],
    defaultVersion: "3.13.2",
    versions: [
      { version: "3.13.2", image: "wiremock/wiremock:3.13.2-2", selectable: true },
      { version: "3.12.1", image: "wiremock/wiremock:3.12.1-2", selectable: false }
    ],
    catalogResourceVersion: "7",
    routing: { mode: "PATH", host: "mock-fleet.test" }
  };
}

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
