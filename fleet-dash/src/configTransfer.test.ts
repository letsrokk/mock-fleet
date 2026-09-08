import { describe, expect, it } from "vitest";
import { exportMockConfigs, parseMockConfigs } from "./configTransfer";
import type { ConfigView } from "./apiContracts";

const saved = { mockId: "orders", version: null, options: ["--verbose"], resources: null };

function configView(): ConfigView {
  return {
    resourceVersion: "12",
    mockIds: ["orders", "payments"],
    savedMockIds: ["orders"],
    mocks: ["orders", "payments"].map((mockId) => ({
      mockId,
      lifecycle: "STOPPED",
      baseline: { options: ["--port", "8080"], resources: { requests: {}, limits: {} } },
      effective: { version: "3.13.2", options: ["--port", "8080", "--verbose"], resources: { requests: {}, limits: {} } },
      user: { version: null, options: ["--verbose"], resources: null },
      wireMockVersion: "3.13.2",
      runtimeVersion: null
    })),
    defaultVersion: "3.13.2",
    versions: [],
    catalogResourceVersion: null,
    routing: { mode: "PATH", host: "localhost" }
  };
}

describe("configuration transfer", () => {
  it("round trips saved overrides only, preserving inherited version and resources", () => {
    const all = exportMockConfigs(configView());
    expect(all).toEqual([saved]);
    expect(parseMockConfigs(JSON.stringify(all))).toEqual([saved]);
    expect(exportMockConfigs(configView(), "orders")).toEqual(all);
    expect(exportMockConfigs(configView(), "payments")).toEqual([]);
  });

  it("imports the same array into a selected ID and preserves pinned values", () => {
    const entry = { ...saved, version: "3.13.2", resources: { requests: { cpu: "100m" }, limits: {} } };
    expect(parseMockConfigs(JSON.stringify([entry]), "payments")).toEqual([{ ...entry, mockId: "payments" }]);
    expect(parseMockConfigs(JSON.stringify([entry]))).toEqual([entry]);
  });

  it("allows empty all imports but requires exactly one entry for individual imports", () => {
    expect(parseMockConfigs("[]")).toEqual([]);
    expect(() => parseMockConfigs("[]", "orders")).toThrow("exactly one");
    expect(() => parseMockConfigs(JSON.stringify([saved, saved]), "orders")).toThrow("exactly one");
  });

  it.each([
    "not json",
    JSON.stringify(saved),
    "[null]",
    JSON.stringify([{ ...saved, options: "--verbose" }]),
    JSON.stringify([{ ...saved, resources: { requests: { cpu: 1 }, limits: {} } }]),
    JSON.stringify([{ ...saved, version: 3 }]),
    JSON.stringify([{ ...saved, unexpected: true }]),
    JSON.stringify([saved, saved])
  ])("rejects malformed or duplicate configuration before confirmation: %s", (text) => {
    expect(() => parseMockConfigs(text)).toThrow();
  });
});
