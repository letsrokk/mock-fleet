import { describe, expect, it } from "vitest";
import {
  draftFromConfig,
  emptyUserConfig,
  hasOption,
  incompatibleOptionNames,
  numberInputAttributes,
  optionsFromDraft,
  overrideOptions,
  resourcesFromDraft,
  wireMockVersionLabel,
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
    values: [],
    minimum: null,
    maximum: null
  },
  {
    name: "--max-request-journal-entries",
    label: "Max journal entries",
    kind: "number",
    group: "Request Journal",
    description: "Sets the maximum number of request journal entries.",
    values: [],
    minimum: 0,
    maximum: 100000
  },
  {
    name: "--use-chunked-encoding",
    label: "Chunked encoding",
    kind: "select",
    group: "HTTP",
    description: "Controls when responses use Transfer-Encoding: chunked.",
    values: ["always", "never", "body_file"],
    minimum: null,
    maximum: null
  },
  {
    name: "--filename-template",
    label: "Filename template",
    kind: "input",
    group: "Request Journal",
    description: "Sets the Handlebars filename template for recorded mappings.",
    values: [],
    minimum: null,
    maximum: null
  }
];

const emptyResources = { requests: {}, limits: {} };

describe("config option helpers", () => {
  it("reports every effective and raw argument unsupported by a target catalog", () => {
    expect(incompatibleOptionNames(["--verbose", "--legacy=on", "--legacy"], definitions)).toEqual(["--legacy"]);
  });
  it("shows only the configured WireMock version in the catalog heading", () => {
    expect(wireMockVersionLabel("3.13.2")).toBe("WireMock 3.13.2");
  });

  it("represents inherited user resources as null", () => {
    expect(emptyUserConfig()).toEqual({ options: [], resources: null });
  });

  it("uses API-provided integer bounds for numeric inputs", () => {
    expect(numberInputAttributes(definitions[1])).toEqual({ min: 0, max: 100000, step: 1 });
    expect(numberInputAttributes(definitions[0])).toEqual({});
  });

  it("uses numeric attributes for optional numeric inputs", () => {
    expect(numberInputAttributes({
      ...definitions[1],
      kind: "optional_number"
    })).toEqual({ min: 0, max: 100000, step: 1 });
  });

  it("round trips optional arguments with and without values", () => {
    const optionalDefinitions: OptionDefinition[] = [{
      ...definitions[1],
      name: "--max-template-cache-entries",
      kind: "optional_number"
    }];

    const withoutValue = draftFromConfig({
      options: ["--max-template-cache-entries"],
      resources: emptyResources
    }, optionalDefinitions);
    expect(withoutValue.flags["--max-template-cache-entries"]).toBe(true);
    expect(optionsFromDraft(withoutValue, optionalDefinitions)).toEqual(["--max-template-cache-entries"]);

    withoutValue.values["--max-template-cache-entries"] = "100";
    expect(optionsFromDraft(withoutValue, optionalDefinitions)).toEqual([
      "--max-template-cache-entries", "100"
    ]);
  });

  it("emits timeout only when its value field is non-empty", () => {
    const timeoutDefinitions: OptionDefinition[] = [{
      ...definitions[3],
      name: "--timeout",
      label: "Timeout ms"
    }];

    expect(optionsFromDraft({
      flags: {}, values: {}, rawArgs: "", requests: {}, limits: {}
    }, timeoutDefinitions)).toEqual([]);
    expect(optionsFromDraft({
      flags: {}, values: { "--timeout": "10000" }, rawArgs: "", requests: {}, limits: {}
    }, timeoutDefinitions)).toEqual(["--timeout", "10000"]);
  });

  it("keeps clearing inherited resources as an explicit empty override", () => {
    const baseline: ConfigData = {
      options: [],
      resources: { requests: { cpu: "0.5" }, limits: { cpu: "1" } }
    };

    expect(resourcesFromDraft({
      flags: {}, values: {}, rawArgs: "", requests: {}, limits: {}
    }, baseline)).toEqual({ requests: {}, limits: {} });
  });

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
      options: ["--filename-template", "{{{method}}}-{{{url}}}.json"],
      resources: emptyResources
    };

    const draft = draftFromConfig(config, definitions);

    expect(draft.values["--filename-template"]).toBe("{{{method}}}-{{{url}}}.json");
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

  it("passes advanced arguments through for authoritative server validation", () => {
    expect(optionsFromDraft({
      flags: {},
      values: {},
      rawArgs: "--not-advertised value stray",
      requests: {},
      limits: {}
    }, definitions)).toEqual(["--not-advertised value stray"]);
  });

  it("keeps quoted advanced arguments intact when baseline options exist", () => {
    expect(optionsFromDraft({
      flags: {},
      values: {},
      rawArgs: "--filename-template '{{{method}}}-{{{url}}}.json'",
      requests: {},
      limits: {}
    }, definitions, {
      options: ["--verbose"],
      resources: emptyResources
    })).toEqual(["--filename-template '{{{method}}}-{{{url}}}.json'"]);
  });

  it("does not hide duplicate options from server validation", () => {
    expect(optionsFromDraft({
      flags: { "--verbose": true },
      values: {},
      rawArgs: "--verbose",
      requests: {},
      limits: {}
    }, definitions)).toEqual(["--verbose", "--verbose"]);
  });
});
