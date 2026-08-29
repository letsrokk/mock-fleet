import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { loadConfigAndOptionCatalog } from "./configCatalog";
import { OptionCatalogPresentation } from "./optionCatalogPresentation";

describe("option catalog presentation", () => {
  it("renders controls from the separate catalog response and one newer-catalog warning", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        mockIds: ["orders"],
        options: [{ name: "--config-only", label: "Config-only option" }]
      }))
      .mockResolvedValueOnce(jsonResponse({
        wireMockVersion: "3.14.0",
        catalogStatus: "newer_unresearched",
        options: [option("--catalog-only", "Catalog-only option")]
      }));
    const { optionCatalog } = await loadConfigAndOptionCatalog(fetcher);

    const markup = renderToStaticMarkup(
      <OptionCatalogPresentation
        catalog={optionCatalog}
        toolbar={null}
        renderOptions={(options) => options.map((definition) => (
          <label key={definition.name}>
            <input aria-label={definition.label} />
            {definition.name}
          </label>
        ))}
      />
    );

    expect(markup).toContain('aria-label="Catalog-only option"');
    expect(markup).not.toContain("Config-only option");
    expect(markup.match(/data-testid="catalog-warning"/g)).toHaveLength(1);
  });

  it("does not render a catalog warning for researched versions", () => {
    const markup = renderToStaticMarkup(
      <OptionCatalogPresentation
        catalog={{
          wireMockVersion: "3.13.2",
          catalogStatus: "supported",
          options: [option("--verbose", "Verbose logging")]
        }}
        toolbar={null}
        renderOptions={() => null}
      />
    );

    expect(markup).not.toContain('data-testid="catalog-warning"');
  });
});

function option(name: string, label: string) {
  return {
    name,
    label,
    kind: "flag" as const,
    group: "Logging",
    description: "Writes more logs.",
    values: [],
    minimum: null,
    maximum: null
  };
}

function jsonResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => ""
  } as Response;
}
