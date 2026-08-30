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
          effective: { options: ["--verbose"], resources: { requests: {}, limits: {} } },
          wireMockVersion: "3.13.2",
          runtimeVersion: "3.12.1"
        }],
        defaultVersion: "3.13.2",
        versions: [{ version: "3.13.2", image: "wiremock/wiremock:3.13.2-2", selectable: true }],
        catalogResourceVersion: "7",
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

  it("does not repeat a structured detail already present in the error message", async () => {
    const response = new Response(JSON.stringify({
      code: "INVALID_OPTIONS",
      message: "WireMock option requires a value: --disable-connection-reuse",
      retryable: false,
      stateMayHaveChanged: false,
      details: { option: "--disable-connection-reuse" }
    }), { status: 400, headers: { "Content-Type": "application/json" } });

    await expect(errorMessage(response, "Unable to save config.")).resolves.toBe(
      "WireMock option requires a value: --disable-connection-reuse [INVALID_OPTIONS]"
    );
  });

  it("keeps distinct details with matching values and option-name prefixes", async () => {
    const response = new Response(JSON.stringify({
      code: "CONFIG_CONFLICT",
      message: "Unknown option --foobar; expected version 42.",
      retryable: true,
      stateMayHaveChanged: false,
      details: { option: "--foo", expectedVersion: "42", currentVersion: "42" }
    }), { status: 409, headers: { "Content-Type": "application/json" } });

    await expect(errorMessage(response, "Unable to save config.")).resolves.toBe(
      "Unknown option --foobar; expected version 42. [CONFIG_CONFLICT] "
      + "option=--foo, expectedVersion=42, currentVersion=42"
    );
  });

  it("does not repeat other structured details embedded in their messages", async () => {
    for (const [message, details] of [
      ["Unsupported WireMock resource: gpu", { resource: "gpu" }],
      ["Invalid WireMock resource quantity: requests.cpu", { field: "requests.cpu" }],
      ["Unsupported config apply mode: restartNever", { applyMode: "restartNever" }]
    ] as const) {
      const response = new Response(JSON.stringify({
        code: "INVALID_REQUEST",
        message,
        retryable: false,
        stateMayHaveChanged: false,
        details
      }), { status: 400, headers: { "Content-Type": "application/json" } });

      await expect(errorMessage(response, "Unable to save config.")).resolves.toBe(
        `${message} [INVALID_REQUEST]`
      );
    }
  });

  it("keeps a non-JSON server message instead of replacing it with a client rule", async () => {
    const response = new Response("Mappings storage is unavailable.", { status: 503 });

    await expect(errorMessage(response, "Unable to load mappings.")).resolves.toBe(
      "Mappings storage is unavailable."
    );
  });
});
