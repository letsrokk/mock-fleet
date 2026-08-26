import { describe, expect, it } from "vitest";
import { mockStatusPresentation } from "./mockStatus";

describe("mock status presentation", () => {
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
