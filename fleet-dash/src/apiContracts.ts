import type { ConfigData, OptionDefinition, UserConfigData } from "./configOptions";

export type MockLifecycle = "STOPPED" | "STARTING" | "RUNNING" | "FAILED";

export type MockConfigView = {
  mockId: string;
  lifecycle: MockLifecycle;
  baseline: ConfigData;
  user: UserConfigData;
  effective: ConfigData;
};

export type RoutingView = {
  mode: "HOST" | "PATH";
  host: string;
};

export type ConfigView = {
  resourceVersion: string | null;
  mockIds: string[];
  savedMockIds: string[];
  mocks: MockConfigView[];
  wireMock: {
    configuredImage: string;
    version: string;
    minimumSupportedVersion: string;
    maximumResearchedVersion: string;
    rangeStatus: "supported" | "newer_unresearched";
  };
  routing: RoutingView;
};

export type OptionCatalogView = {
  wireMockVersion: string;
  catalogStatus: "supported" | "newer_unresearched";
  options: OptionDefinition[];
};

export type ApplyMode = "futureOnly" | "restartActive";

export type ConfigMutationResult = {
  config: ConfigView;
  apply: {
    mockId: string;
    mode: ApplyMode;
    lifecycle: MockLifecycle;
  };
};

type ApiError = {
  code: string;
  message: string;
  retryable: boolean;
  stateMayHaveChanged: boolean;
  details: Record<string, unknown>;
};

export function isActiveLifecycle(lifecycle: MockLifecycle) {
  return lifecycle === "STARTING" || lifecycle === "RUNNING";
}

export function lifecycleLabel(lifecycle: MockLifecycle) {
  switch (lifecycle) {
    case "STARTING":
      return "Pod starting";
    case "RUNNING":
      return "Active pod running";
    case "FAILED":
      return "Pod startup failed";
    case "STOPPED":
      return "Future pods";
  }
}

export function configMutation(result: ConfigMutationResult) {
  return result;
}

export async function errorMessage(response: Response, fallback: string) {
  const text = (await response.text()).trim();
  if (!text) {
    return fallback;
  }

  try {
    const parsed = JSON.parse(text) as unknown;
    if (isApiError(parsed)) {
      const details = Object.entries(parsed.details)
        .map(([key, value]) => `${key}=${formatDetail(value)}`)
        .join(", ");
      return `${parsed.message} [${parsed.code}]${details ? ` ${details}` : ""}`;
    }
  } catch {
    // A non-JSON response is still useful server feedback.
  }
  return text;
}

function isApiError(value: unknown): value is ApiError {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const candidate = value as Partial<ApiError>;
  return typeof candidate.code === "string"
    && typeof candidate.message === "string"
    && typeof candidate.retryable === "boolean"
    && typeof candidate.stateMayHaveChanged === "boolean"
    && typeof candidate.details === "object"
    && candidate.details !== null
    && !Array.isArray(candidate.details);
}

function formatDetail(value: unknown) {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean"
    ? String(value)
    : JSON.stringify(value);
}
