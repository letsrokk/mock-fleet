import { describe, expect, it } from "vitest";
import appSource from "./App.tsx?raw";

describe("dashboard header structure", () => {
  it("uses the existing favicon as the Mock Fleet brand mark", () => {
    expect(appSource).toContain('<img className="brand-mark" src="./favicon.svg" alt="" aria-hidden="true" />');
    expect(appSource).not.toContain('className="brand-mark" aria-hidden="true">MF');
  });

  it("keeps refresh actions in panel headers rather than beside navigation", () => {
    const pageBar = appSource.slice(appSource.indexOf('<div className="page-bar">'), appSource.indexOf('</header>'));

    expect(pageBar).not.toContain('className="refresh-button"');
    expect(appSource).not.toContain("Manual refresh");
    expect(appSource.match(/className="panel-refresh-button"/g)).toHaveLength(4);
  });

  it("keeps retry actions available after initial Config and Mappings failures", () => {
    expect(appSource).not.toContain("if (loadingConfig || configView === null)");
    expect(appSource).toContain("mappingsLoaded && !mappingsView.enabled && !mappingsStatusError");
    expect(appSource).toContain('tab === "mappings" && mappingsLoaded && !mappingsView.enabled && !mappingsStatusError');
    expect(appSource).toContain('? "Loading configuration..."\n        : "Configuration unavailable.";');
    expect(appSource).toContain('mappingsStatusError ? "Mappings unavailable." :');
  });
});
