import { describe, expect, it } from "vitest";
import {
  configMutation,
  errorMessage,
  isActiveLifecycle,
  lifecycleLabel,
  type ConfigMutationResult
} from "./apiContracts";

describe("Fleet API dashboard contracts", () => {
  it.each([
    ["STARTING", true],
    ["RUNNING", true],
    ["FAILED", false],
    ["STOPPED", false]
  ] as const)("treats %s as active=%s for restart prompts", (lifecycle, active) => {
    expect(isActiveLifecycle(lifecycle)).toBe(active);
  });

  it.each([
    ["STARTING", "Pod starting"],
    ["RUNNING", "Active pod running"],
    ["FAILED", "Pod startup failed"],
    ["STOPPED", "Future pods"]
  ] as const)("labels %s configuration lifecycle", (lifecycle, label) => {
    expect(lifecycleLabel(lifecycle)).toBe(label);
  });

  it("unwraps lifecycle-bearing config mutation responses", () => {
    const mutation = {
      config: {
        resourceVersion: "43",
        mockIds: ["orders"],
        savedMockIds: ["orders"],
        mocks: [{
          mockId: "orders",
          lifecycle: "STARTING",
          baseline: { options: [], resources: { requests: {}, limits: {} } },
          user: { options: ["--verbose"], resources: null },
          effective: { options: ["--verbose"], resources: { requests: {}, limits: {} } }
        }],
        wireMock: {
          configuredImage: "wiremock/wiremock:3.13.2-2",
          version: "3.13.2",
          minimumSupportedVersion: "3.0.0",
          maximumResearchedVersion: "3.13.2",
          rangeStatus: "supported"
        },
        options: [],
        routing: { mode: "PATH", host: "mock-fleet.test" }
      },
      apply: { mockId: "orders", mode: "restartActive", lifecycle: "STARTING" }
    } satisfies ConfigMutationResult;

    expect(configMutation(mutation)).toEqual({
      config: mutation.config,
      apply: { mockId: "orders", mode: "restartActive", lifecycle: "STARTING" }
    });
  });

  it("displays structured API errors with reconciliation details", async () => {
    const response = new Response(JSON.stringify({
      code: "CONFIG_CONFLICT",
      message: "WireMock config was modified by another writer.",
      retryable: true,
      stateMayHaveChanged: false,
      details: { expectedVersion: "42", currentVersion: "43" }
    }), { status: 409, headers: { "Content-Type": "application/json" } });

    await expect(errorMessage(response, "Unable to save config.")).resolves.toBe(
      "WireMock config was modified by another writer. [CONFIG_CONFLICT] "
      + "expectedVersion=42, currentVersion=43"
    );
  });

  it("keeps a non-JSON server message instead of replacing it with a client rule", async () => {
    const response = new Response("Mappings storage is unavailable.", { status: 503 });

    await expect(errorMessage(response, "Unable to load mappings.")).resolves.toBe(
      "Mappings storage is unavailable."
    );
  });
});
