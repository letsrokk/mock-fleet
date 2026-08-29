import type { ReactNode } from "react";
import { wireMockVersionLabel, type OptionDefinition } from "./configOptions";
import type { OptionCatalogView } from "./apiContracts";

type OptionCatalogPresentationProps = {
  catalog: OptionCatalogView;
  toolbar: ReactNode;
  renderOptions: (options: OptionDefinition[]) => ReactNode;
};

export function OptionCatalogPresentation({ catalog, toolbar, renderOptions }: OptionCatalogPresentationProps) {
  return (
    <>
      <div className="section-title-row">
        <span>
          <h2>WireMock options</h2>
          <span className="option-version-context">{wireMockVersionLabel(catalog.wireMockVersion)}</span>
        </span>
        {toolbar}
      </div>
      {catalog.catalogStatus === "newer_unresearched" ? (
        <p className="notice warning compact-notice" role="status" data-testid="catalog-warning">
          This WireMock version is newer than the compatibility matrix. Known options remain usable, but compatibility is unknown.
        </p>
      ) : null}
      <div className="option-list">{renderOptions(catalog.options)}</div>
    </>
  );
}
