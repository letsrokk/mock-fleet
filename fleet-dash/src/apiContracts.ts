import type { ConfigData, OptionDefinition, UserConfigData } from "./configOptions";

export type MockLifecycle = "STOPPED" | "STARTING" | "RUNNING" | "FAILED";

export type MockConfigView = {
  mockId: string;
  lifecycle: MockLifecycle;
  baseline: ConfigData;
  user: UserConfigData;
  effective: ConfigData;
  wireMockVersion: string;
  runtimeVersion: string | null;
};

export type VersionView = { version: string; image: string; selectable: boolean };

export type RoutingView = {
  mode: "HOST" | "PATH";
  host: string;
};

export type ConfigView = {
  resourceVersion: string | null;
  mockIds: string[];
  savedMockIds: string[];
  mocks: MockConfigView[];
  defaultVersion: string;
  versions: VersionView[];
  catalogResourceVersion: string | null;
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

export function wireMockVersionOptions(versions: VersionView[], selectedVersion: string) {
  return versions
    .filter((version) => version.selectable || version.version === selectedVersion)
    .sort((left, right) => compareWireMockVersions(right.version, left.version));
}

function compareWireMockVersions(left: string, right: string) {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  return leftParts[0] - rightParts[0]
    || leftParts[1] - rightParts[1]
    || leftParts[2] - rightParts[2];
}

export async function errorMessage(response: Response, fallback: string) {
  const text = (await response.text()).trim();
  if (!text) {
    return fallback;
  }

  try {
    const parsed = JSON.parse(text) as unknown;
    if (isApiError(parsed)) {
      const detailEntries = Object.entries(parsed.details);
      const detailValueCounts = detailEntries.reduce((counts, [, value]) => {
        const formatted = formatDetail(value);
        counts.set(formatted, (counts.get(formatted) ?? 0) + 1);
        return counts;
      }, new Map<string, number>());
      const details = detailEntries
        .filter(([, value]) => detailValueCounts.get(formatDetail(value)) !== 1
          || !containsExactDetail(parsed.message, value))
        .map(([key, value]) => `${key}=${formatDetail(value)}`)
        .join(", ");
      return `${parsed.message} [${parsed.code}]${details ? ` ${details}` : ""}`;
    }
  } catch {
    // A non-JSON response is still useful server feedback.
  }
  return text;
}

function containsExactDetail(message: string, value: unknown) {
  const formattedValue = formatDetail(value);
  if (!formattedValue) {
    return false;
  }
  const escapedValue = formattedValue.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|[^\\w-])${escapedValue}(?=$|[^\\w-])`).test(message);
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
