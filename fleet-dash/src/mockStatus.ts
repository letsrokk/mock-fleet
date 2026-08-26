export type MockStatus = "STARTING" | "RUNNING" | "FAILED";

type MockStatusPresentation = {
  label: string;
  className: string;
  detail: string | null;
};

export function mockStatusPresentation(status: MockStatus, message: string | null): MockStatusPresentation {
  if (status === "STARTING") {
    return { label: "Starting", className: "starting", detail: null };
  }
  if (status === "FAILED") {
    return { label: "Failed", className: "failed", detail: message };
  }
  return { label: "Running", className: "running", detail: null };
}
