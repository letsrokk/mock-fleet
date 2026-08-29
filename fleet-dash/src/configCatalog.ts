import { errorMessage, type ConfigView, type OptionCatalogView } from "./apiContracts";

const CONFIG_API_PATH = "/__fleet/api/config";
const CONFIG_OPTIONS_API_PATH = "/__fleet/api/config/options";

export async function loadConfigAndOptionCatalog(fetcher: typeof fetch = fetch) {
  const configResponse = await fetcher(CONFIG_API_PATH);
  if (!configResponse.ok) {
    throw new Error(await errorMessage(configResponse, `Unable to load config (${configResponse.status})`));
  }

  const optionCatalogResponse = await fetcher(CONFIG_OPTIONS_API_PATH);
  if (!optionCatalogResponse.ok) {
    throw new Error(await errorMessage(
      optionCatalogResponse,
      `Unable to load WireMock option catalog (${optionCatalogResponse.status})`
    ));
  }

  return {
    config: (await configResponse.json()) as ConfigView,
    optionCatalog: (await optionCatalogResponse.json()) as OptionCatalogView
  };
}
