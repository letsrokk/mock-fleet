export type OptionDefinition = {
  name: string;
  label: string;
  kind: "flag" | "input" | "number" | "select" | "optional_number" | "optional_input";
  group: string;
  description: string;
  values: string[];
  minimum: number | null;
  maximum: number | null;
};

export type ResourceData = {
  requests: Record<string, string>;
  limits: Record<string, string>;
};

export type ConfigData = {
  version?: string | null;
  options: string[];
  resources: ResourceData;
};

export type UserConfigData = {
  version?: string | null;
  options: string[];
  resources: ResourceData | null;
};

export type DraftConfig = {
  flags: Record<string, boolean>;
  values: Record<string, string>;
  rawArgs: string;
  requests: Record<string, string>;
  limits: Record<string, string>;
};

export function emptyConfig(): ConfigData {
  return { options: [], resources: { requests: {}, limits: {} } };
}

export function emptyUserConfig(): UserConfigData {
  return { options: [], resources: null };
}

export function emptyDraft(): DraftConfig {
  return { flags: {}, values: {}, rawArgs: "", requests: {}, limits: {} };
}

export function groupOptions(options: OptionDefinition[]) {
  const grouped = new Map<string, OptionDefinition[]>();
  options.forEach((option) => {
    const group = option.group || "Other";
    grouped.set(group, [...(grouped.get(group) ?? []), option]);
  });
  return Array.from(grouped.entries());
}

export function draftFromConfig(config: ConfigData, definitions: OptionDefinition[]): DraftConfig {
  const draft = emptyDraft();
  const valueOptions = new Set(definitions.filter((option) => option.kind !== "flag").map((option) => option.name));
  const flagOptions = new Set(definitions.filter((option) => option.kind === "flag").map((option) => option.name));
  const optionalOptions = new Set(definitions
    .filter((option) => option.kind.startsWith("optional_"))
    .map((option) => option.name));
  const booleanSelectOptions = new Set(definitions
    .filter((option) => option.kind === "select" && option.values.join(",") === "true,false")
    .map((option) => option.name));
  const rawArgs: string[] = [];
  const optionTokens = normalizeOptions(config.options);

  for (let index = 0; index < optionTokens.length; index += 1) {
    const token = optionTokens[index];
    const equalsIndex = token.indexOf("=");
    const name = equalsIndex > 0 ? token.slice(0, equalsIndex) : token;
    if (flagOptions.has(token)) {
      draft.flags[token] = true;
    } else if (valueOptions.has(name)) {
      if (equalsIndex > 0) {
        draft.values[name] = token.slice(equalsIndex + 1);
      } else {
        const nextValue = optionTokens[index + 1];
        if (nextValue && !nextValue.startsWith("--")) {
          draft.values[token] = nextValue;
          index += 1;
        } else if (booleanSelectOptions.has(name)) {
          draft.values[name] = "true";
        } else if (optionalOptions.has(name)) {
          draft.flags[name] = true;
        } else {
          rawArgs.push(token);
        }
      }
    } else {
      rawArgs.push(token);
    }
  }

  draft.rawArgs = rawArgs.join(" ");
  draft.requests = { ...config.resources.requests };
  draft.limits = { ...config.resources.limits };
  return draft;
}

export function optionsFromDraft(draft: DraftConfig, definitions: OptionDefinition[], baseline?: ConfigData) {
  const options: string[] = [];
  definitions.forEach((definition) => {
    if (definition.kind === "flag" && draft.flags[definition.name]) {
      options.push(definition.name);
    }
    if (definition.kind !== "flag") {
      const value = draft.values[definition.name]?.trim();
      if (value) {
        options.push(definition.name, value);
      } else if (definition.kind.startsWith("optional_") && draft.flags[definition.name]) {
        options.push(definition.name);
      }
    }
  });
  const rawArgs = draft.rawArgs.trim() ? [draft.rawArgs.trim()] : [];
  const overrides = baseline ? overrideOptions(options, baseline.options) : options;
  return [...overrides, ...rawArgs];
}

export function resourcesFromDraft(draft: DraftConfig, baseline?: ConfigData): ResourceData | null {
  const requests = cleanRecord(draft.requests);
  const limits = cleanRecord(draft.limits);
  if (baseline && recordsEqual(requests, baseline.resources.requests) && recordsEqual(limits, baseline.resources.limits)) {
    return null;
  }
  return baseline || Object.keys(requests).length || Object.keys(limits).length ? { requests, limits } : null;
}

export function splitArgs(value: string) {
  return value.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((token) => token.replace(/^"|"$/g, "")) ?? [];
}

export function overrideOptions(effectiveOptions: string[], baselineOptions: string[]) {
  const baselineEntries = new Map<string, string[]>();
  parseOptionEntries(baselineOptions).forEach((entry) => {
    if (entry.name) {
      baselineEntries.set(entry.name, entry.tokens);
    }
  });

  return parseOptionEntries(effectiveOptions)
    .filter((entry) => !entry.name || !tokensEqual(entry.tokens, baselineEntries.get(entry.name)))
    .flatMap((entry) => entry.tokens);
}

export function hasOption(options: string[], name: string) {
  return parseOptionEntries(options).some((entry) => entry.name === name);
}

export function incompatibleOptionNames(options: string[], definitions: OptionDefinition[]) {
  const known = new Set(definitions.map((definition) => definition.name));
  return Array.from(new Set(parseOptionEntries(options)
    .map((entry) => entry.name)
    .filter((name): name is string => name !== null && !known.has(name)))).sort();
}

export function incompatibleDraftOptionNames(
  savedOptions: string[],
  draft: DraftConfig,
  definitions: OptionDefinition[]
) {
  const structuredOptions = [
    ...Object.entries(draft.flags)
      .filter(([, enabled]) => enabled)
      .map(([name]) => name),
    ...Object.entries(draft.values)
      .filter(([, value]) => value.trim())
      .map(([name, value]) => `${name}=${value.trim()}`)
  ];
  const rawArgs = draft.rawArgs.trim() ? [draft.rawArgs.trim()] : [];
  return incompatibleOptionNames([...savedOptions, ...structuredOptions, ...rawArgs], definitions);
}

export function recordsEqual(left: Record<string, string>, right: Record<string, string>) {
  const cleanRight = cleanRecord(right);
  const leftEntries = Object.entries(left);
  return leftEntries.length === Object.keys(cleanRight).length
    && leftEntries.every(([key, value]) => cleanRight[key] === value);
}

export function resourceSummary(resources: ResourceData) {
  const requestText = Object.entries(resources.requests).map(([key, value]) => `${key}=${value}`).join(", ");
  const limitText = Object.entries(resources.limits).map(([key, value]) => `${key}=${value}`).join(", ");
  return `requests: ${requestText || "none"}; limits: ${limitText || "none"}`;
}

export function wireMockVersionLabel(version: string) {
  return `WireMock ${version}`;
}

export function numberInputAttributes(option: OptionDefinition) {
  if (!option.kind.endsWith("number") || option.minimum === null || option.maximum === null) {
    return {};
  }
  return { min: option.minimum, max: option.maximum, step: 1 };
}

function cleanRecord(values: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(values)
      .map(([key, value]) => [key, value.trim()])
      .filter(([, value]) => value)
  );
}

function normalizeOptions(options: string[]) {
  return options.flatMap((option) => {
    const trimmed = option.trim();
    return trimmed.startsWith("--") ? splitArgs(trimmed) : [option];
  });
}

function parseOptionEntries(options: string[]) {
  const entries: Array<{ name: string | null; tokens: string[] }> = [];
  const optionTokens = normalizeOptions(options);
  for (let index = 0; index < optionTokens.length; index += 1) {
    const token = optionTokens[index];
    const name = optionName(token);
    const tokens = [token];
    if (name && !token.includes("=") && optionTokens[index + 1] && !optionTokens[index + 1].startsWith("--")) {
      tokens.push(optionTokens[index + 1]);
      index += 1;
    }
    entries.push({ name, tokens });
  }
  return entries;
}

function optionName(token: string) {
  if (!token.startsWith("--")) {
    return null;
  }
  const equalsIndex = token.indexOf("=");
  return equalsIndex > 0 ? token.slice(0, equalsIndex) : token;
}

function tokensEqual(left: string[], right?: string[]) {
  if (right === undefined) {
    return false;
  }
  if (left.length === right.length && left.every((value, index) => value === right[index])) {
    return true;
  }
  return optionName(left[0]) === optionName(right[0]) && optionValue(left) === optionValue(right);
}

function optionValue(tokens: string[]) {
  const equalsIndex = tokens[0].indexOf("=");
  if (equalsIndex > 0) {
    return tokens[0].slice(equalsIndex + 1);
  }
  return tokens[1] ?? null;
}
