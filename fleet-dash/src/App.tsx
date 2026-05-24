import { useEffect, useMemo, useRef, useState } from "react";
import refreshIcon from "./assets/refresh.svg";
import trashIcon from "./assets/trash.svg";

type MockRow = {
  mockId: string;
  podName: string;
};

type OptionDefinition = {
  name: string;
  label: string;
  kind: "flag" | "input" | "number" | "select";
  group: string;
  description: string;
  values: string[];
};

type ResourceData = {
  requests: Record<string, string>;
  limits: Record<string, string>;
};

type ConfigData = {
  options: string[];
  resources: ResourceData;
};

type MockConfigView = {
  mockId: string;
  active: boolean;
  baseline: ConfigData;
  user: ConfigData;
  effective: ConfigData;
};

type ConfigView = {
  resourceVersion: string | null;
  mockIds: string[];
  mocks: MockConfigView[];
  options: OptionDefinition[];
};

type DraftConfig = {
  flags: Record<string, boolean>;
  values: Record<string, string>;
  rawArgs: string;
  requests: Record<string, string>;
  limits: Record<string, string>;
};

type ConfirmDialogState = {
  title: string;
  body: string;
  confirmLabel: string;
  danger: boolean;
  onConfirm: () => void | Promise<void>;
};

const MOCKS_API_PATH = "/__fleet/api/mocks";
const CONFIG_API_PATH = "/__fleet/api/config";
const RESOURCE_KEYS = ["cpu", "memory"];
const VALID_MOCK_ID = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const MOCK_ID_VALIDATION_MESSAGE =
  "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";

type Tab = "mocks" | "config";

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>(() => tabFromHash());
  const [rows, setRows] = useState<MockRow[]>([]);
  const [configView, setConfigView] = useState<ConfigView | null>(null);
  const [selectedMockId, setSelectedMockId] = useState<string | null>(null);
  const [draft, setDraft] = useState<DraftConfig>(emptyDraft());
  const [newMockId, setNewMockId] = useState("");
  const [loadingMocks, setLoadingMocks] = useState(true);
  const [loadingConfig, setLoadingConfig] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyMockId, setBusyMockId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [configDirty, setConfigDirty] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [confirmDialog, setConfirmDialog] = useState<ConfirmDialogState | null>(null);
  const [confirming, setConfirming] = useState(false);
  const mountedRef = useRef(true);
  const toastTimerRef = useRef<number | null>(null);

  const selectedMock = useMemo(
    () => configView?.mocks.find((mock) => mock.mockId === selectedMockId) ?? null,
    [configView, selectedMockId]
  );

  async function loadMocks(showSpinner: boolean) {
    if (showSpinner) {
      setLoadingMocks(true);
    } else {
      setRefreshing(true);
    }

    try {
      const response = await fetch(MOCKS_API_PATH);
      if (!response.ok) {
        throw new Error(`Unable to load mocks (${response.status})`);
      }
      const data = (await response.json()) as MockRow[];
      setError(null);
      setRows(data);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Unable to load mocks.");
    } finally {
      if (mountedRef.current) {
        setLoadingMocks(false);
        setRefreshing(false);
      }
    }
  }

  async function loadConfig(showSpinner: boolean) {
    if (showSpinner) {
      setLoadingConfig(true);
    } else {
      setRefreshing(true);
    }

    try {
      const response = await fetch(CONFIG_API_PATH);
      if (!response.ok) {
        throw new Error(`Unable to load config (${response.status})`);
      }
      const data = (await response.json()) as ConfigView;
      const preserveDraft = configDirty && selectedMockId !== null;
      const nextData = preserveDraft && selectedMockId && !data.mockIds.includes(selectedMockId)
        ? withLocalMock(data, selectedMockId)
        : data;
      const nextSelected = selectedMockId && nextData.mockIds.includes(selectedMockId)
        ? selectedMockId
        : nextData.mockIds[0] ?? null;
      setConfigView(nextData);
      setSelectedMockId(nextSelected);
      if (nextSelected && !preserveDraft) {
        const mock = nextData.mocks.find((item) => item.mockId === nextSelected);
        setDraft(draftFromConfig(mock?.user ?? emptyConfig(), nextData.options));
        setConfigDirty(false);
      }
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Unable to load config.");
    } finally {
      if (mountedRef.current) {
        setLoadingConfig(false);
        setRefreshing(false);
      }
    }
  }

  async function killMock(mockId: string) {
    setBusyMockId(mockId);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(`${MOCKS_API_PATH}/${encodeURIComponent(mockId)}`, {
        method: "DELETE"
      });
      if (response.status === 404) {
        throw new Error(`Mock '${mockId}' no longer exists.`);
      }
      if (!response.ok) {
        throw new Error(`Unable to delete mock '${mockId}'.`);
      }
      setRows((currentRows) => currentRows.filter((row) => row.mockId !== mockId));
      showToast(`Deleted mock '${mockId}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete mock.");
    } finally {
      setBusyMockId(null);
    }
  }

  async function saveConfig() {
    if (!selectedMockId || !configView) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(`${CONFIG_API_PATH}/${encodeURIComponent(selectedMockId)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          resourceVersion: configView.resourceVersion,
          options: optionsFromDraft(draft, configView.options),
          resources: resourcesFromDraft(draft)
        })
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response, "Unable to save config."));
      }
      const data = (await response.json()) as ConfigView;
      setConfigView(data);
      setSelectedMockId(selectedMockId);
      const mock = data.mocks.find((item) => item.mockId === selectedMockId);
      setDraft(draftFromConfig(mock?.user ?? emptyConfig(), data.options));
      setConfigDirty(false);
      showToast(`Saved config for '${selectedMockId}'.`);
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : "Unable to save config.");
    } finally {
      setSaving(false);
    }
  }

  async function deleteOverride() {
    if (!selectedMockId || !configView) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(`${CONFIG_API_PATH}/${encodeURIComponent(selectedMockId)}`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ resourceVersion: configView.resourceVersion })
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response, "Unable to delete override."));
      }
      const data = (await response.json()) as ConfigView;
      setConfigView(data);
      const nextSelected = data.mockIds.includes(selectedMockId) ? selectedMockId : data.mockIds[0] ?? null;
      setSelectedMockId(nextSelected);
      const mock = data.mocks.find((item) => item.mockId === nextSelected);
      setDraft(draftFromConfig(mock?.user ?? emptyConfig(), data.options));
      setConfigDirty(false);
      showToast(`Deleted override for '${selectedMockId}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete override.");
    } finally {
      setSaving(false);
    }
  }

  function addMockId() {
    const mockId = newMockId.trim();
    if (!mockId || !configView) {
      return;
    }
    if (!VALID_MOCK_ID.test(mockId)) {
      setError(MOCK_ID_VALIDATION_MESSAGE);
      return;
    }
    setError(null);
    if (configView.mockIds.includes(mockId)) {
      setSelectedMockId(mockId);
      setNewMockId("");
      setConfigDirty(false);
      return;
    }
    setConfigView({
      ...configView,
      mockIds: [...configView.mockIds, mockId].sort(),
      mocks: [
        ...configView.mocks,
        { mockId, active: false, baseline: emptyConfig(), user: emptyConfig(), effective: emptyConfig() }
      ].sort((left, right) => left.mockId.localeCompare(right.mockId))
    });
    setSelectedMockId(mockId);
    setDraft(emptyDraft());
    setConfigDirty(true);
    setNewMockId("");
  }

  function selectMock(mockId: string) {
    setSelectedMockId(mockId);
    const mock = configView?.mocks.find((item) => item.mockId === mockId);
    setDraft(draftFromConfig(mock?.user ?? emptyConfig(), configView?.options ?? []));
    setConfigDirty(false);
  }

  function selectTab(tab: Tab) {
    const nextHash = tab === "mocks" ? "#active" : "#config";
    if (window.location.hash === nextHash) {
      setActiveTab(tab);
      return;
    }
    window.location.hash = nextHash;
  }

  function resetDraft() {
    if (!selectedMock || !configView) {
      return;
    }
    setDraft(draftFromConfig(selectedMock.user, configView.options));
    setConfigDirty(false);
  }

  function requestKillMock(mockId: string) {
    setConfirmDialog({
      title: "Delete active mock?",
      body: `Delete active mock '${mockId}' and its running pod? The next request can recreate it.`,
      confirmLabel: "Delete mock",
      danger: true,
      onConfirm: () => killMock(mockId)
    });
  }

  function requestResetConfig() {
    if (!selectedMockId) {
      return;
    }
    setConfirmDialog({
      title: "Reset mock config?",
      body: `Discard unsaved config changes for '${selectedMockId}' and restore the saved override values?`,
      confirmLabel: "Reset",
      danger: false,
      onConfirm: resetDraft
    });
  }

  function requestDeleteOverride() {
    if (!selectedMockId) {
      return;
    }
    setConfirmDialog({
      title: "Delete mock config?",
      body: `Delete the saved config override for '${selectedMockId}' from the user ConfigMap?`,
      confirmLabel: "Delete config",
      danger: true,
      onConfirm: deleteOverride
    });
  }

  async function confirmAction() {
    if (!confirmDialog) {
      return;
    }
    setConfirming(true);
    try {
      await confirmDialog.onConfirm();
      setConfirmDialog(null);
    } finally {
      setConfirming(false);
    }
  }

  function showToast(nextMessage: string) {
    setMessage(nextMessage);
    if (toastTimerRef.current !== null) {
      window.clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = window.setTimeout(() => {
      setMessage(null);
      toastTimerRef.current = null;
    }, 2600);
  }

  useEffect(() => {
    mountedRef.current = true;

    function syncTabFromHash() {
      setActiveTab(tabFromHash());
    }

    window.addEventListener("hashchange", syncTabFromHash);
    syncTabFromHash();

    return () => {
      mountedRef.current = false;
      window.removeEventListener("hashchange", syncTabFromHash);
      if (toastTimerRef.current !== null) {
        window.clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (activeTab !== "mocks") {
      return;
    }

    void loadMocks(true);

    const intervalId = window.setInterval(() => {
      void loadMocks(false);
    }, 5000);

    return () => {
      window.clearInterval(intervalId);
    };
  }, [activeTab]);

  useEffect(() => {
    if (activeTab === "config" && configView === null) {
      void loadConfig(true);
    }
  }, [activeTab, configView]);

  useEffect(() => {
    if (!confirmDialog) {
      return;
    }

    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape" && !confirming) {
        setConfirmDialog(null);
      }
    }

    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [confirmDialog, confirming]);

  return (
    <main className="shell">
      {message ? <div className="toast success">{message}</div> : null}
      {confirmDialog ? renderConfirmDialog() : null}

      <section className="hero">
        <p className="eyebrow">Mock Fleet</p>
        <div className="hero-row">
          <div>
            <h1>{activeTab === "mocks" ? "Active Mocks" : "Configuration"}</h1>
            <p className="subtitle">
              {activeTab === "mocks"
                ? "Inspect currently active mocks and remove them before inactivity cleanup runs."
                : "Edit per-mock startup options stored in the user ConfigMap."}
            </p>
          </div>
        </div>
        <div className="tab-controls">
          <div className="tabs" role="tablist" aria-label="Mock Fleet views">
            <button className={activeTab === "mocks" ? "tab active" : "tab"} onClick={() => selectTab("mocks")}>
              Active Mocks
            </button>
            <button className={activeTab === "config" ? "tab active" : "tab"} onClick={() => selectTab("config")}>
              Configuration
            </button>
          </div>
          <button
            className="refresh-button"
            onClick={() => activeTab === "mocks" ? void loadMocks(false) : void loadConfig(false)}
            disabled={loadingMocks || loadingConfig || refreshing}
            aria-label={refreshing ? "Refreshing" : "Refresh"}
          >
            {loadingMocks || loadingConfig || refreshing ? (
              <span className="refresh-spinner" aria-hidden="true"></span>
            ) : (
              <img src={refreshIcon} alt="" aria-hidden="true" className="refresh-icon" />
            )}
          </button>
        </div>
      </section>

      {error ? <p className="notice error">{error}</p> : null}

      {activeTab === "mocks" ? renderMocksPanel() : renderConfigPanel()}
    </main>
  );

  function renderMocksPanel() {
    return (
      <section className="panel">
        <div className="panel-header">
          <span>{rows.length} active mocks</span>
          <span className="panel-status">{refreshing ? "Updating..." : "Auto-refresh every 5s"}</span>
        </div>

        {loadingMocks ? <p className="state">Loading active mocks...</p> : null}
        {!loadingMocks && rows.length === 0 ? <p className="state">No active mocks.</p> : null}

        {!loadingMocks && rows.length > 0 ? (
          <table>
            <thead>
              <tr>
                <th>Mock ID</th>
                <th>Pod Name</th>
                <th aria-label="Actions"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.mockId}>
                  <td className="mono">{row.mockId}</td>
                  <td className="mono">{row.podName}</td>
                  <td className="actions">
                    <button
                      className="danger-button"
                      onClick={() => requestKillMock(row.mockId)}
                      disabled={busyMockId === row.mockId}
                      aria-label={busyMockId === row.mockId ? "Deleting" : `Delete ${row.mockId}`}
                    >
                      {busyMockId === row.mockId ? (
                        <span className="delete-spinner" aria-hidden="true"></span>
                      ) : (
                        <img src={trashIcon} alt="" aria-hidden="true" className="trash-icon" />
                      )}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    );
  }

  function renderConfigPanel() {
    if (loadingConfig || configView === null) {
      return <section className="panel"><p className="state">Loading mocks config...</p></section>;
    }

    return (
      <section className="config-layout">
        <aside className="panel mock-list">
          <div className="panel-header">
            <span>{configView.mockIds.length} mock ids</span>
            <span className="panel-status">{refreshing ? "Updating..." : configDirty ? "Unsaved changes" : "Manual refresh"}</span>
          </div>
          <div className="add-row">
            <input
              value={newMockId}
              onChange={(event) => setNewMockId(event.target.value)}
              onKeyDown={(event) => event.key === "Enter" ? addMockId() : undefined}
              placeholder="mock-id"
              aria-label="New mock id"
            />
            <button className="primary-button" onClick={addMockId}>Add</button>
          </div>
          <div className="mock-buttons">
            {configView.mockIds.map((mockId) => (
              <button
                key={mockId}
                className={selectedMockId === mockId ? "mock-button active" : "mock-button"}
                onClick={() => selectMock(mockId)}
              >
                <span className="mono">{mockId}</span>
                {configView.mocks.find((mock) => mock.mockId === mockId)?.active ? <span className="badge">active</span> : null}
              </button>
            ))}
          </div>
        </aside>

        <section className="panel editor-panel">
          {selectedMock ? (
            <>
              <div className="panel-header">
                <span className="mono">{selectedMock.mockId}</span>
                <span className="panel-status">Changes apply to future pods</span>
              </div>
              <div className="editor-body">
                <div className="form-section">
                  <h2>Available options</h2>
                  <div className="option-list">
                    {groupOptions(configView.options).map(([group, options]) => (
                      <section className="option-group" key={group}>
                        <h3>{group}</h3>
                        <div className="option-group-list">
                          {options.map((option) => renderOptionControl(option))}
                        </div>
                      </section>
                    ))}
                  </div>
                </div>

                <div className="form-section">
                  <h2>Resources</h2>
                  <div className="resource-grid">
                    {RESOURCE_KEYS.map((key) => (
                      <label key={`request-${key}`}>
                        Request {key}
                        <input
                          value={draft.requests[key] ?? ""}
                          onChange={(event) => setDraftField("requests", key, event.target.value)}
                          placeholder={key === "cpu" ? "0.5" : "512Mi"}
                        />
                      </label>
                    ))}
                    {RESOURCE_KEYS.map((key) => (
                      <label key={`limit-${key}`}>
                        Limit {key}
                        <input
                          value={draft.limits[key] ?? ""}
                          onChange={(event) => setDraftField("limits", key, event.target.value)}
                          placeholder={key === "cpu" ? "1" : "1Gi"}
                        />
                      </label>
                    ))}
                  </div>
                </div>

                <label className="form-section">
                  <h2>Advanced args</h2>
                  <textarea
                    value={draft.rawArgs}
                    onChange={(event) => {
                      setConfigDirty(true);
                      setDraft((current) => ({ ...current, rawArgs: event.target.value }));
                    }}
                    rows={4}
                    spellCheck={false}
                  />
                </label>

                <div className="summary-grid">
                  <ConfigSummary title="Baseline" data={selectedMock.baseline} />
                  <ConfigSummary title="Effective" data={selectedMock.effective} />
                </div>
              </div>
              <div className="editor-actions">
                <button
                  className="secondary-button"
                  onClick={requestResetConfig}
                  disabled={saving}
                >
                  Reset
                </button>
                <button className="danger-text-button" onClick={requestDeleteOverride} disabled={saving}>
                  Delete override
                </button>
                <button className="primary-button" onClick={() => void saveConfig()} disabled={saving}>
                  {saving ? "Saving..." : "Save"}
                </button>
              </div>
            </>
          ) : (
            <p className="state">Add a mock id to create an editable override.</p>
          )}
        </section>
      </section>
    );
  }

  function renderOptionControl(option: OptionDefinition) {
    if (option.kind === "flag") {
      return (
        <div className="option-row" key={option.name}>
          <label className="check-row">
            <input
              type="checkbox"
              checked={Boolean(draft.flags[option.name])}
              onChange={(event) => {
                setConfigDirty(true);
                setDraft((current) => ({
                  ...current,
                  flags: { ...current.flags, [option.name]: event.target.checked }
                }));
              }}
            />
            <OptionText option={option} />
          </label>
        </div>
      );
    }

    return (
      <div className="option-row" key={option.name}>
        <label className="field-row">
          <OptionText option={option} />
          {option.kind === "select" ? (
            <select
              value={draft.values[option.name] ?? ""}
              onChange={(event) => setDraftValue(option.name, event.target.value)}
            >
              <option value=""></option>
              {option.values.map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          ) : (
            <input
              type={option.kind === "number" ? "number" : "text"}
              value={draft.values[option.name] ?? ""}
              onChange={(event) => setDraftValue(option.name, event.target.value)}
            />
          )}
        </label>
      </div>
    );
  }

  function setDraftValue(name: string, value: string) {
    setConfigDirty(true);
    setDraft((current) => ({ ...current, values: { ...current.values, [name]: value } }));
  }

  function setDraftField(area: "requests" | "limits", key: string, value: string) {
    setConfigDirty(true);
    setDraft((current) => ({ ...current, [area]: { ...current[area], [key]: value } }));
  }

  function renderConfirmDialog() {
    if (!confirmDialog) {
      return null;
    }

    return (
      <div className="dialog-backdrop" role="presentation">
        <div
          className="confirm-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-dialog-title"
        >
          <h2 id="confirm-dialog-title">{confirmDialog.title}</h2>
          <p>{confirmDialog.body}</p>
          <div className="dialog-actions">
            <button
              className="secondary-button"
              onClick={() => setConfirmDialog(null)}
              disabled={confirming}
            >
              Cancel
            </button>
            <button
              className={confirmDialog.danger ? "danger-text-button" : "primary-button"}
              onClick={() => void confirmAction()}
              disabled={confirming}
            >
              {confirming ? "Working..." : confirmDialog.confirmLabel}
            </button>
          </div>
        </div>
      </div>
    );
  }
}

function OptionText({ option }: { option: OptionDefinition }) {
  return (
    <span className="option-text">
      <span className="option-title">
        <span>{option.label}</span>
        <span className="option-name">{option.name}</span>
      </span>
      <span className="option-description">{option.description}</span>
    </span>
  );
}

function ConfigSummary({ title, data }: { title: string; data: ConfigData }) {
  return (
    <div className="summary">
      <h2>{title}</h2>
      <p className="mono compact">{data.options.length > 0 ? data.options.join(" ") : "No args"}</p>
      <p className="mono compact">{resourceSummary(data.resources)}</p>
    </div>
  );
}

function emptyConfig(): ConfigData {
  return { options: [], resources: { requests: {}, limits: {} } };
}

function emptyDraft(): DraftConfig {
  return { flags: {}, values: {}, rawArgs: "", requests: {}, limits: {} };
}

function withLocalMock(configView: ConfigView, mockId: string): ConfigView {
  if (configView.mockIds.includes(mockId)) {
    return configView;
  }
  return {
    ...configView,
    mockIds: [...configView.mockIds, mockId].sort(),
    mocks: [
      ...configView.mocks,
      { mockId, active: false, baseline: emptyConfig(), user: emptyConfig(), effective: emptyConfig() }
    ].sort((left, right) => left.mockId.localeCompare(right.mockId))
  };
}

function groupOptions(options: OptionDefinition[]) {
  const grouped = new Map<string, OptionDefinition[]>();
  options.forEach((option) => {
    const group = option.group || "Other";
    grouped.set(group, [...(grouped.get(group) ?? []), option]);
  });
  return Array.from(grouped.entries());
}

function draftFromConfig(config: ConfigData, definitions: OptionDefinition[]): DraftConfig {
  const draft = emptyDraft();
  const valueOptions = new Set(definitions.filter((option) => option.kind !== "flag").map((option) => option.name));
  const flagOptions = new Set(definitions.filter((option) => option.kind === "flag").map((option) => option.name));
  const rawArgs: string[] = [];

  for (let index = 0; index < config.options.length; index += 1) {
    const token = config.options[index];
    const equalsIndex = token.indexOf("=");
    const name = equalsIndex > 0 ? token.slice(0, equalsIndex) : token;
    if (flagOptions.has(token)) {
      draft.flags[token] = true;
    } else if (valueOptions.has(name)) {
      if (equalsIndex > 0) {
        draft.values[name] = token.slice(equalsIndex + 1);
      } else {
        const nextValue = config.options[index + 1];
        if (nextValue && !nextValue.startsWith("--")) {
          draft.values[token] = nextValue;
          index += 1;
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

function optionsFromDraft(draft: DraftConfig, definitions: OptionDefinition[]) {
  const options: string[] = [];
  definitions.forEach((definition) => {
    if (definition.kind === "flag" && draft.flags[definition.name]) {
      options.push(definition.name);
    }
    if (definition.kind !== "flag") {
      const value = draft.values[definition.name]?.trim();
      if (value) {
        options.push(definition.name, value);
      }
    }
  });
  return [...options, ...splitArgs(draft.rawArgs)];
}

function resourcesFromDraft(draft: DraftConfig): ResourceData | null {
  const requests = cleanRecord(draft.requests);
  const limits = cleanRecord(draft.limits);
  return Object.keys(requests).length || Object.keys(limits).length ? { requests, limits } : null;
}

function cleanRecord(values: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(values)
      .map(([key, value]) => [key, value.trim()])
      .filter(([, value]) => value)
  );
}

function splitArgs(value: string) {
  return value.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((token) => token.replace(/^"|"$/g, "")) ?? [];
}

function resourceSummary(resources: ResourceData) {
  const requestText = Object.entries(resources.requests).map(([key, value]) => `${key}=${value}`).join(", ");
  const limitText = Object.entries(resources.limits).map(([key, value]) => `${key}=${value}`).join(", ");
  return `requests: ${requestText || "none"}; limits: ${limitText || "none"}`;
}

function tabFromHash(): Tab {
  return window.location.hash === "#config" ? "config" : "mocks";
}

async function errorMessage(response: Response, fallback: string) {
  if (response.status === 409) {
    return "Config changed. Refresh and retry.";
  }
  const body = await response.text();
  return body.trim() || fallback;
}
