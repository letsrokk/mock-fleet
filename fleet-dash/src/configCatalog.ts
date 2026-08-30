import { errorMessage, type ConfigView, type OptionCatalogView } from "./apiContracts";

const CONFIG_API_PATH = "/__fleet/api/config";
const CONFIG_OPTIONS_API_PATH = "/__fleet/api/config/options";

export type ConfigCatalogLoad = {
  config: ConfigView;
  optionCatalog: OptionCatalogView;
};

export type EditorTarget = {
  mockId: string;
  draftWireMockVersion: string | null;
  desiredVersion: string;
};

export class LatestConfigRequest {
  private request = 0;

  begin() {
    this.request += 1;
    return this.request;
  }

  invalidate() {
    this.request += 1;
  }

  isCurrent(request: number) {
    return request === this.request;
  }
}

export function editorTarget(config: ConfigView, mockId: string): EditorTarget {
  const mock = config.mocks.find((item) => item.mockId === mockId);
  let draftWireMockVersion: string | null = null;
  if (mock) {
    if (config.savedMockIds.includes(mockId)) {
      draftWireMockVersion = mock.user.version ?? null;
    } else if (mock.wireMockVersion !== config.defaultVersion) {
      draftWireMockVersion = mock.wireMockVersion;
    }
  }
  return {
    mockId,
    draftWireMockVersion,
    desiredVersion: draftWireMockVersion ?? config.defaultVersion
  };
}

export async function loadEditorCatalog(
  config: ConfigView,
  mockId: string | null,
  draftWireMockVersion: string | null | undefined,
  fetcher: typeof fetch = fetch
) {
  const target = mockId === null
    ? {
      mockId: "",
      draftWireMockVersion: null,
      desiredVersion: config.defaultVersion
    }
    : draftWireMockVersion === undefined
      ? editorTarget(config, mockId)
      : {
        mockId,
        draftWireMockVersion,
        desiredVersion: draftWireMockVersion ?? config.defaultVersion
      };
  return {
    target,
    catalog: await loadOptionCatalogForVersion(target.desiredVersion, fetcher)
  };
}

export async function loadConfigAndOptionCatalog(fetcher: typeof fetch = fetch) {
  const config = await loadConfigView(fetcher);
  return {
    config,
    optionCatalog: await loadOptionCatalogForVersion(config.defaultVersion, fetcher)
  };
}

export async function loadConfigView(fetcher: typeof fetch = fetch) {
  const configResponse = await fetcher(CONFIG_API_PATH);
  if (!configResponse.ok) {
    throw new Error(await errorMessage(configResponse, `Unable to load config (${configResponse.status})`));
  }
  return (await configResponse.json()) as ConfigView;
}

export async function loadOptionCatalogForVersion(version: string, fetcher: typeof fetch = fetch) {
  const optionCatalogResponse = await fetcher(`${CONFIG_OPTIONS_API_PATH}?version=${encodeURIComponent(version)}`);
  if (!optionCatalogResponse.ok) {
    throw new Error(await errorMessage(
      optionCatalogResponse,
      `Unable to load WireMock option catalog (${optionCatalogResponse.status})`
    ));
  }
  const catalog = (await optionCatalogResponse.json()) as OptionCatalogView;
  if (catalog.wireMockVersion !== version) {
    throw new Error(`Unable to load WireMock option catalog: expected ${version}, received ${catalog.wireMockVersion}.`);
  }
  return catalog;
}

export async function refreshConfigAndOptionCatalog(
  previous: ConfigCatalogLoad | null,
  fetcher: typeof fetch = fetch
): Promise<{ ok: true; state: ConfigCatalogLoad } | { ok: false; state: ConfigCatalogLoad | null; error: string }> {
  try {
    return { ok: true, state: await loadConfigAndOptionCatalog(fetcher) };
  } catch (error) {
    return {
      ok: false,
      state: previous,
      error: error instanceof Error ? error.message : "Unable to load config."
    };
  }
}
