import { errorMessage, type ConfigView, type OptionCatalogView } from "./apiContracts";

const CONFIG_API_PATH = "/__fleet/api/config";
const CONFIG_OPTIONS_API_PATH = "/__fleet/api/config/options";

export type ConfigCatalogLoad = {
  config: ConfigView;
  optionCatalog: OptionCatalogView;
};

export async function loadConfigAndOptionCatalog(fetcher: typeof fetch = fetch) {
  const configResponse = await fetcher(CONFIG_API_PATH);
  if (!configResponse.ok) {
    throw new Error(await errorMessage(configResponse, `Unable to load config (${configResponse.status})`));
  }

  const config = (await configResponse.json()) as ConfigView;
  const optionCatalogResponse = await fetcher(`${CONFIG_OPTIONS_API_PATH}?version=${encodeURIComponent(config.defaultVersion)}`);
  if (!optionCatalogResponse.ok) {
    throw new Error(await errorMessage(
      optionCatalogResponse,
      `Unable to load WireMock option catalog (${optionCatalogResponse.status})`
    ));
  }

  return {
    config,
    optionCatalog: (await optionCatalogResponse.json()) as OptionCatalogView
  };
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
