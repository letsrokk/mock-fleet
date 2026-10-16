import { describe, expect, it } from "vitest";
import { mockStatusPresentation } from "./mockStatus";

describe("mock status presentation", () => {
  it("explains when startup is waiting for the previous pod to terminate", () => {
    expect(mockStatusPresentation("STARTING", "Waiting for previous pod to terminate.")).toEqual({
      label: "Waiting",
      className: "starting",
      detail: "Waiting for previous pod to terminate."
    });
  });

  it("shows startup progress without an error message", () => {
    expect(mockStatusPresentation("STARTING", null)).toEqual({
      label: "Starting",
      className: "starting",
      detail: null
    });
  });

  it("shows a failed startup reason", () => {
    expect(mockStatusPresentation("FAILED", "Image pull failed")).toEqual({
      label: "Failed",
      className: "failed",
      detail: "Image pull failed"
    });
  });

  it("shows a running mock without an error message", () => {
    expect(mockStatusPresentation("RUNNING", null)).toEqual({
      label: "Running",
      className: "running",
      detail: null
    });
  });
});
