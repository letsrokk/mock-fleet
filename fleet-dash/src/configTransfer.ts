import type { ConfigView } from "./apiContracts";
import type { ResourceData } from "./configOptions";

export type MockConfigExport = {
  mockId: string;
  version: string | null;
  options: string[];
  resources: ResourceData | null;
};

export function exportMockConfigs(config: ConfigView, mockId?: string): MockConfigExport[] {
  return config.savedMockIds
    .filter((id) => mockId === undefined || id === mockId)
    .map((id) => {
      const mock = config.mocks.find((entry) => entry.mockId === id);
      if (!mock) throw new Error(`Saved config for '${id}' is unavailable. Refresh and retry.`);
      return { mockId: id, version: mock.user.version ?? null, options: mock.user.options, resources: mock.user.resources };
    });
}

export function parseMockConfigs(text: string, targetMockId?: string): MockConfigExport[] {
  const value: unknown = JSON.parse(text);
  if (!Array.isArray(value)) throw new Error("Configuration must be a JSON array, including for one mock.");
  if (targetMockId !== undefined && value.length !== 1) {
    throw new Error("Individual import requires exactly one mock in the array.");
  }
  const ids = new Set<string>();
  return value.map((entry: unknown) => {
    if (!isRecord(entry)
      || typeof entry.mockId !== "string"
      || !(entry.version === null || typeof entry.version === "string")
      || !Array.isArray(entry.options) || !entry.options.every((option) => typeof option === "string")
      || !(entry.resources === null || isRecord(entry.resources)
        && isStringMap(entry.resources.requests) && isStringMap(entry.resources.limits))
      || Object.keys(entry).some((key) => !["mockId", "version", "options", "resources"].includes(key))) {
      throw new Error("Each mock must contain mockId, version, options, and resources in the export format.");
    }
    const mockId = targetMockId ?? entry.mockId;
    if (ids.has(mockId)) throw new Error(`Duplicate mock ID '${mockId}'.`);
    ids.add(mockId);
    return { mockId, version: entry.version, options: entry.options, resources: entry.resources } as MockConfigExport;
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isStringMap(value: unknown) {
  return isRecord(value) && Object.values(value).every((item) => typeof item === "string");
}
