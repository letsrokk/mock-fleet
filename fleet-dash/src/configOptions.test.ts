import { describe, expect, it } from "vitest";
import {
  draftFromConfig,
  hasOption,
  optionsFromDraft,
  overrideOptions,
  type ConfigData,
  type OptionDefinition
} from "./configOptions";

const definitions: OptionDefinition[] = [
  {
    name: "--verbose",
    label: "Verbose logging",
    kind: "flag",
    group: "Logging",
    description: "Log more detail to stdout.",
    values: []
  },
  {
    name: "--max-request-journal-entries",
    label: "Max journal entries",
    kind: "number",
    group: "Request Journal",
    description: "Sets the maximum number of request journal entries.",
    values: []
  },
  {
    name: "--use-chunked-encoding",
    label: "Chunked encoding",
    kind: "select",
    group: "HTTP",
    description: "Controls when responses use Transfer-Encoding: chunked.",
    values: ["always", "never", "body_file"]
  },
  {
    name: "--filename-template",
    label: "Filename template",
    kind: "input",
    group: "Request Journal",
    description: "Sets the Handlebars filename template for recorded mappings.",
    values: []
  }
];

const emptyResources = { requests: {}, limits: {} };

describe("config option helpers", () => {
  it("maps combined default CLI options into structured draft fields", () => {
    const config: ConfigData = {
      options: ["--verbose --max-request-journal-entries 10"],
      resources: emptyResources
    };

    const draft = draftFromConfig(config, definitions);

    expect(draft.flags["--verbose"]).toBe(true);
    expect(draft.values["--max-request-journal-entries"]).toBe("10");
    expect(draft.rawArgs).toBe("");
  });

  it("serializes only a changed value option when overriding combined baseline args", () => {
    const baseline: ConfigData = {
      options: ["--verbose --max-request-journal-entries 10"],
      resources: emptyResources
    };
    const draft = draftFromConfig(baseline, definitions);
    draft.values["--max-request-journal-entries"] = "15";

    expect(optionsFromDraft(draft, definitions, baseline)).toEqual([
      "--max-request-journal-entries",
      "15"
    ]);
  });

  it("keeps already tokenized options compatible", () => {
    const config: ConfigData = {
      options: ["--verbose", "--max-request-journal-entries", "10"],
      resources: emptyResources
    };

    const draft = draftFromConfig(config, definitions);

    expect(draft.flags["--verbose"]).toBe(true);
    expect(draft.values["--max-request-journal-entries"]).toBe("10");
    expect(optionsFromDraft(draft, definitions, config)).toEqual([]);
  });

  it("does not split separate value tokens that contain spaces", () => {
    const config: ConfigData = {
      options: ["--filename-template", "{{request.method}} {{request.url}}"],
      resources: emptyResources
    };

    const draft = draftFromConfig(config, definitions);

    expect(draft.values["--filename-template"]).toBe("{{request.method}} {{request.url}}");
  });

  it("compares equals syntax with split value syntax", () => {
    expect(overrideOptions(
      ["--max-request-journal-entries", "10", "--use-chunked-encoding", "never"],
      ["--max-request-journal-entries=10", "--use-chunked-encoding=always"]
    )).toEqual(["--use-chunked-encoding", "never"]);
  });

  it("normalizes combined options when checking inherited flags", () => {
    expect(hasOption(["--verbose --max-request-journal-entries 10"], "--verbose")).toBe(true);
    expect(hasOption(["--verbose --max-request-journal-entries 10"], "--max-request-journal-entries")).toBe(true);
  });

  it("leaves unknown options in advanced args after normalization", () => {
    const config: ConfigData = {
      options: ["--verbose --unknown-option 1"],
      resources: emptyResources
    };

    const draft = draftFromConfig(config, definitions);

    expect(draft.flags["--verbose"]).toBe(true);
    expect(draft.rawArgs).toBe("--unknown-option 1");
  });
});
