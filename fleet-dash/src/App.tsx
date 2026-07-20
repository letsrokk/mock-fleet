import { useEffect, useMemo, useRef, useState } from "react";
import refreshIcon from "./assets/refresh.svg";
import trashIcon from "./assets/trash.svg";
import {
  draftFromConfig,
  emptyConfig,
  emptyDraft,
  groupOptions,
  hasOption,
  optionsFromDraft,
  resourceSummary,
  resourcesFromDraft,
  type ConfigData,
  type DraftConfig,
  type OptionDefinition
} from "./configOptions";

type MockRow = {
  mockId: string;
  podName: string;
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
  routing: RoutingView;
};

type RoutingView = {
  mode: "HOST" | "PATH";
  host: string;
};

type ConfirmDialogState = {
  title: string;
  body: string;
  confirmLabel: string;
  secondaryLabel?: string;
  danger: boolean;
  onConfirm: () => void | Promise<void>;
  onSecondary?: () => void | Promise<void>;
};

type MappingsView = {
  enabled: boolean;
  mockIds: string[];
  error?: string | null;
};

type ApplyMode = "futureOnly" | "restartActive";

type MappingFileNode = {
  name: string;
  path: string;
  directory: boolean;
  children: MappingFileNode[];
};

const MOCKS_API_PATH = "/__fleet/api/mocks";
const MOCKS_STREAM_PATH = `${MOCKS_API_PATH}/stream`;
const CONFIG_API_PATH = "/__fleet/api/config";
const MAPPINGS_API_PATH = "/__fleet/api/mappings";
const RESOURCE_GROUP_NAME = "Resources";
const RESOURCE_KEYS = ["cpu", "memory"];
const VALID_MOCK_ID = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const MOCK_ID_VALIDATION_MESSAGE =
  "Mock id must contain 1-63 lowercase letters, numbers, or hyphens, and must start and end with a letter or number.";

type Tab = "mocks" | "config" | "mappings";

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>(() => tabFromHash());
  const [rows, setRows] = useState<MockRow[]>([]);
  const [configView, setConfigView] = useState<ConfigView | null>(null);
  const [mappingsView, setMappingsView] = useState<MappingsView>({ enabled: false, mockIds: [] });
  const [mappingsLoaded, setMappingsLoaded] = useState(false);
  const [mappingsStatusError, setMappingsStatusError] = useState<string | null>(null);
  const [mappingsTree, setMappingsTree] = useState<MappingFileNode | null>(null);
  const [collapsedMappingPaths, setCollapsedMappingPaths] = useState<Set<string>>(() => new Set());
  const [collapsedOptionGroups, setCollapsedOptionGroups] = useState<Set<string>>(() => new Set());
  const [selectedMockId, setSelectedMockId] = useState<string | null>(null);
  const [selectedMappingsMockId, setSelectedMappingsMockId] = useState<string | null>(null);
  const [draft, setDraft] = useState<DraftConfig>(emptyDraft());
  const [newMockId, setNewMockId] = useState("");
  const [loadingMocks, setLoadingMocks] = useState(true);
  const [sseConnected, setSseConnected] = useState(false);
  const [loadingConfig, setLoadingConfig] = useState(false);
  const [loadingMappings, setLoadingMappings] = useState(false);
  const [loadingMappingsTree, setLoadingMappingsTree] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyMockId, setBusyMockId] = useState<string | null>(null);
  const [busyMappingPath, setBusyMappingPath] = useState<string | null>(null);
  const [busyMappingFolder, setBusyMappingFolder] = useState<string | null>(null);
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
  const activeTabLoading = activeTab === "mocks"
    ? loadingMocks
    : activeTab === "config"
      ? loadingConfig
      : loadingMappings || loadingMappingsTree;

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
      const data = normalizeConfigView((await response.json()) as ConfigView & { routing?: RoutingView });
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
        setDraft(draftFromConfig(mock?.effective ?? emptyConfig(), nextData.options));
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

  async function loadMappings(showSpinner: boolean) {
    if (showSpinner) {
      setLoadingMappings(true);
    } else if (activeTab === "mappings") {
      setRefreshing(true);
    }

    try {
      const response = await fetch(MAPPINGS_API_PATH);
      if (!response.ok) {
        throw new Error(`Unable to load mappings (${response.status})`);
      }
      const data = (await response.json()) as MappingsView;
      const nextSelected = selectedMappingsMockId && data.mockIds.includes(selectedMappingsMockId)
        ? selectedMappingsMockId
        : data.mockIds[0] ?? null;
      setMappingsView(data);
      setMappingsLoaded(true);
      setMappingsStatusError(data.error ?? null);
      setSelectedMappingsMockId(nextSelected);
      if (!data.enabled || nextSelected === null) {
        setMappingsTree(null);
        setCollapsedMappingPaths(new Set());
      } else {
        await loadMappingsTree(nextSelected, false);
      }
      setError(null);
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : "Unable to load mappings.";
      setMappingsLoaded(true);
      setMappingsStatusError(message);
      setError(message);
    } finally {
      if (mountedRef.current) {
        setLoadingMappings(false);
        setRefreshing(false);
      }
    }
  }

  async function loadMappingsTree(mockId: string, showSpinner: boolean) {
    if (showSpinner) {
      setLoadingMappingsTree(true);
    }

    try {
      const response = await fetch(`${MAPPINGS_API_PATH}/${encodeURIComponent(mockId)}/tree`);
      if (!response.ok) {
        throw new Error(await errorMessage(response, `Unable to load mappings for '${mockId}'.`));
      }
      const data = (await response.json()) as MappingFileNode;
      setMappingsTree(data);
      setCollapsedMappingPaths(new Set());
      setError(null);
    } catch (loadError) {
      setMappingsTree(null);
      setCollapsedMappingPaths(new Set());
      setError(loadError instanceof Error ? loadError.message : "Unable to load mappings tree.");
    } finally {
      if (mountedRef.current) {
        setLoadingMappingsTree(false);
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

  async function saveConfig(applyMode: ApplyMode) {
    if (!selectedMockId || !configView) {
      return;
    }

    let nextOptions: string[];
    try {
      nextOptions = optionsFromDraft(draft, configView.options, selectedMock?.baseline ?? emptyConfig());
    } catch (validationError) {
      setError(validationError instanceof Error ? validationError.message : "Invalid WireMock arguments.");
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
          options: nextOptions,
          resources: resourcesFromDraft(draft, selectedMock?.baseline ?? emptyConfig()),
          applyMode
        })
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response, "Unable to save config."));
      }
      const data = (await response.json()) as ConfigView;
      setConfigView(data);
      setSelectedMockId(selectedMockId);
      const mock = data.mocks.find((item) => item.mockId === selectedMockId);
      setDraft(draftFromConfig(mock?.effective ?? emptyConfig(), data.options));
      setConfigDirty(false);
      showToast(`Saved config for '${selectedMockId}'.`);
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : "Unable to save config.");
    } finally {
      setSaving(false);
    }
  }

  async function deleteOverride(applyMode: ApplyMode) {
    if (!selectedMockId || !configView) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(`${CONFIG_API_PATH}/${encodeURIComponent(selectedMockId)}`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ resourceVersion: configView.resourceVersion, applyMode })
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response, "Unable to delete override."));
      }
      const data = (await response.json()) as ConfigView;
      setConfigView(data);
      const nextSelected = data.mockIds.includes(selectedMockId) ? selectedMockId : data.mockIds[0] ?? null;
      setSelectedMockId(nextSelected);
      const mock = data.mocks.find((item) => item.mockId === nextSelected);
      setDraft(draftFromConfig(mock?.effective ?? emptyConfig(), data.options));
      setConfigDirty(false);
      showToast(`Deleted override for '${selectedMockId}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete override.");
    } finally {
      setSaving(false);
    }
  }

  async function deleteMappingFile(path: string) {
    if (!selectedMappingsMockId) {
      return;
    }
    setBusyMappingPath(path);
    setError(null);
    try {
      const response = await fetch(mappingFileUrl(selectedMappingsMockId, path), {
        method: "DELETE"
      });
      if (!response.ok) {
        await loadMappingsTree(selectedMappingsMockId, false);
        throw new Error(await errorMessage(response, "Unable to delete mapping file."));
      }
      await loadMappingsTree(selectedMappingsMockId, false);
      showToast(`Deleted mapping file '${path}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete mapping file.");
    } finally {
      setBusyMappingPath(null);
    }
  }

  async function deleteMappingFolder(mockId: string) {
    setBusyMappingFolder(mockId);
    setError(null);
    try {
      const response = await fetch(mappingFolderUrl(mockId), {
        method: "DELETE"
      });
      if (!response.ok) {
        await loadMappings(false);
        throw new Error(await errorMessage(response, "Unable to delete mappings folder."));
      }
      await loadMappings(false);
      setSelectedMappingsMockId(null);
      setMappingsTree(null);
      showToast(`Deleted mappings folder '${mockId}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete mappings folder.");
    } finally {
      setBusyMappingFolder(null);
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
    setDraft(draftFromConfig(mock?.effective ?? emptyConfig(), configView?.options ?? []));
    setConfigDirty(false);
  }

  function selectMappingsMock(mockId: string) {
    setSelectedMappingsMockId(mockId);
    setCollapsedMappingPaths(new Set());
    void loadMappingsTree(mockId, true);
  }

  function selectTab(tab: Tab) {
    if (tab === "mappings" && !mappingsView.enabled) {
      return;
    }
    const nextHash = tabHash(tab);
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
    setDraft(draftFromConfig(selectedMock.effective, configView.options));
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

  function requestSaveConfig() {
    if (!selectedMock?.active) {
      void saveConfig("futureOnly");
      return;
    }
    setConfirmDialog({
      title: "Apply config to active mock?",
      body: `Mock '${selectedMock.mockId}' has an active pod. Apply these changes only to future pods, or restart the active pod so the next request uses the new config?`,
      confirmLabel: "Apply and restart active pod",
      secondaryLabel: "Apply to future pods",
      danger: false,
      onConfirm: () => saveConfig("restartActive"),
      onSecondary: () => saveConfig("futureOnly")
    });
  }

  function requestDeleteOverride() {
    if (!selectedMockId) {
      return;
    }
    if (selectedMock?.active) {
      setConfirmDialog({
        title: "Delete config for active mock?",
        body: `Mock '${selectedMock.mockId}' has an active pod. Delete the override only for future pods, or restart the active pod so the next request uses the updated config?`,
        confirmLabel: "Delete and restart active pod",
        secondaryLabel: "Delete for future pods",
        danger: true,
        onConfirm: () => deleteOverride("restartActive"),
        onSecondary: () => deleteOverride("futureOnly")
      });
      return;
    }
    setConfirmDialog({
      title: "Delete mock config?",
      body: `Delete the saved config override for '${selectedMockId}' from the user ConfigMap?`,
      confirmLabel: "Delete config",
      danger: true,
      onConfirm: () => deleteOverride("futureOnly")
    });
  }

  function requestDeleteMappingFile(node: MappingFileNode) {
    if (!selectedMappingsMockId || node.directory) {
      return;
    }
    setConfirmDialog({
      title: "Delete mapping file?",
      body: `Delete '${node.path}' from mappings for '${selectedMappingsMockId}'?`,
      confirmLabel: "Delete file",
      danger: true,
      onConfirm: () => deleteMappingFile(node.path)
    });
  }

  function requestDeleteMappingFolder() {
    if (!selectedMappingsMockId) {
      return;
    }
    const mockId = selectedMappingsMockId;
    setConfirmDialog({
      title: "Delete mappings folder?",
      body: `Delete all persisted mapping files for '${mockId}'? This does not delete the active mock or its configuration.`,
      confirmLabel: "Delete folder",
      danger: true,
      onConfirm: () => deleteMappingFolder(mockId)
    });
  }

  function openMappingFile(node: MappingFileNode) {
    if (!selectedMappingsMockId || node.directory) {
      return;
    }
    window.open(mappingFileUrl(selectedMappingsMockId, node.path), "_blank", "noopener,noreferrer");
  }

  function toggleMappingFolder(path: string) {
    setCollapsedMappingPaths((current) => {
      const next = new Set(current);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  }

  function expandAllMappingFolders() {
    setCollapsedMappingPaths(new Set());
  }

  function collapseAllMappingFolders() {
    if (!mappingsTree) {
      return;
    }
    setCollapsedMappingPaths(collectDirectoryPaths(mappingsTree));
  }

  function toggleOptionGroup(group: string) {
    setCollapsedOptionGroups((current) => {
      const next = new Set(current);
      if (next.has(group)) {
        next.delete(group);
      } else {
        next.add(group);
      }
      return next;
    });
  }

  function expandAllOptionGroups() {
    setCollapsedOptionGroups(new Set());
  }

  function collapseAllOptionGroups() {
    if (!configView) {
      return;
    }
    setCollapsedOptionGroups(new Set(optionGroupNames(configView.options)));
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

  async function confirmSecondaryAction() {
    if (!confirmDialog?.onSecondary) {
      return;
    }
    setConfirming(true);
    try {
      await confirmDialog.onSecondary();
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
    void loadMappings(false);

    return () => {
      mountedRef.current = false;
      window.removeEventListener("hashchange", syncTabFromHash);
      if (toastTimerRef.current !== null) {
        window.clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    setLoadingMocks(true);
    setSseConnected(false);
    const eventSource = new EventSource(MOCKS_STREAM_PATH);

    eventSource.onopen = () => {
      if (mountedRef.current) {
        setSseConnected(true);
        setError(null);
      }
    };

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data) as MockRow[];
        if (mountedRef.current) {
          setRows(data);
          setLoadingMocks(false);
          setError(null);
        }
      } catch {
        if (mountedRef.current) {
          setError("Unable to read active mocks stream.");
          setLoadingMocks(false);
        }
      }
    };

    eventSource.onerror = () => {
      if (mountedRef.current) {
        setSseConnected(false);
        setLoadingMocks(false);
      }
    };

    return () => {
      eventSource.close();
      if (mountedRef.current) {
        setSseConnected(false);
      }
    };
  }, []);

  useEffect(() => {
    if (activeTab === "config" && configView === null) {
      void loadConfig(true);
    }
  }, [activeTab, configView]);

  useEffect(() => {
    if (activeTab === "mappings" && mappingsView.enabled) {
      void loadMappings(true);
    }
  }, [activeTab, mappingsView.enabled]);

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
            <h1>{tabTitle(activeTab)}</h1>
            <p className="subtitle">{tabSubtitle(activeTab, mappingsView.enabled)}</p>
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
            <button
              className={activeTab === "mappings" ? "tab active" : "tab"}
              onClick={() => selectTab("mappings")}
              disabled={mappingsLoaded && !mappingsView.enabled}
              title={mappingsTabTitle(mappingsLoaded, mappingsView.enabled, mappingsStatusError)}
            >
              Persisted Mappings
            </button>
          </div>
          {activeTab !== "mocks" ? (
            <button
              className="refresh-button"
              onClick={() => refreshActiveTab()}
              disabled={activeTabLoading || refreshing}
              aria-label={refreshing ? "Refreshing" : "Refresh"}
            >
              {activeTabLoading || refreshing ? (
                <span className="refresh-spinner" aria-hidden="true"></span>
              ) : (
                <img src={refreshIcon} alt="" aria-hidden="true" className="refresh-icon" />
              )}
            </button>
          ) : null}
        </div>
      </section>

      {error ? <p className="notice error">{error}</p> : null}

      {activeTab === "mocks" ? renderMocksPanel() : null}
      {activeTab === "config" ? renderConfigPanel() : null}
      {activeTab === "mappings" ? renderMappingsPanel() : null}
      <footer className="version-footer" aria-label="Application version">
        <span>Version <span className="mono">{__APP_VERSION__}</span></span>
      </footer>
    </main>
  );

  function refreshActiveTab() {
    if (activeTab === "config") {
      void loadConfig(false);
    } else if (activeTab === "mappings") {
      void loadMappings(false);
    }
  }

  function renderMocksPanel() {
    return (
      <section className="panel">
        <div className="panel-header">
          <span>{rows.length} active mocks</span>
          <span
            className={`sse-status ${sseConnected ? "connected" : "waiting"}`}
            aria-label={sseConnected ? "SSE connected" : "SSE waiting for connection"}
          >
            SSE
          </span>
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

    const groupedOptions = groupOptions(configView.options);
    const resourcesCollapsed = collapsedOptionGroups.has(RESOURCE_GROUP_NAME);

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
                <span className="mock-button-text">
                  <span className="mono">{mockId}</span>
                  <span className="mock-base-url">{mockBaseUrl(mockId, configView.routing)}</span>
                </span>
                {configView.mocks.find((mock) => mock.mockId === mockId)?.active ? <span className="badge">active</span> : null}
              </button>
            ))}
          </div>
        </aside>

        <section className="panel editor-panel">
          {selectedMock ? (
            <>
              <div className="panel-header">
                <span className="mock-heading">
                  <span className="mono">{selectedMock.mockId}</span>
                  <a href={mockBaseUrl(selectedMock.mockId, configView.routing)} target="_blank" rel="noreferrer">
                    {mockBaseUrl(selectedMock.mockId, configView.routing)}
                  </a>
                </span>
                <span className="panel-status">{selectedMock.active ? "Active pod running" : "Future pods"}</span>
              </div>
              <div className="editor-body">
                <div className="form-section">
                  <div className="section-title-row">
                    <h2>Available options</h2>
                    <div className="option-toolbar">
                      <button className="secondary-button small-button" type="button" onClick={expandAllOptionGroups}>
                        Expand all
                      </button>
                      <button className="secondary-button small-button" type="button" onClick={collapseAllOptionGroups}>
                        Collapse all
                      </button>
                    </div>
                  </div>
                  <div className="option-list">
                    {groupedOptions.map(([group, options]) => {
                      const collapsed = collapsedOptionGroups.has(group);
                      return (
                      <section className="option-group" key={group}>
                        <button
                          className="option-group-header"
                          type="button"
                          onClick={() => toggleOptionGroup(group)}
                          aria-expanded={!collapsed}
                        >
                          <span className="option-group-icon" aria-hidden="true">{collapsed ? ">" : "v"}</span>
                          <span>{group}</span>
                          <span className="option-group-count">{options.length}</span>
                        </button>
                        {!collapsed ? (
                          <div className="option-group-list">
                            {options.map((option) => renderOptionControl(option))}
                          </div>
                        ) : null}
                      </section>
                      );
                    })}
                    <section className="option-group" key={RESOURCE_GROUP_NAME}>
                      <button
                        className="option-group-header"
                        type="button"
                        onClick={() => toggleOptionGroup(RESOURCE_GROUP_NAME)}
                        aria-expanded={!resourcesCollapsed}
                      >
                        <span className="option-group-icon" aria-hidden="true">{resourcesCollapsed ? ">" : "v"}</span>
                        <span>{RESOURCE_GROUP_NAME}</span>
                        <span className="option-group-count">{RESOURCE_KEYS.length * 2}</span>
                      </button>
                      {!resourcesCollapsed ? (
                        <div className="resource-grid option-group-list">
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
                      ) : null}
                    </section>
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
                <button className="primary-button" onClick={requestSaveConfig} disabled={saving}>
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

  function renderMappingsPanel() {
    if (!mappingsView.enabled) {
      return (
        <section className="panel">
          <p className="state">
            {mappingsLoaded
              ? "Persistent mappings storage is disabled."
              : "Checking mappings storage..."}
          </p>
        </section>
      );
    }

    return (
      <section className="config-layout">
        <aside className="panel mock-list">
          <div className="panel-header">
            <span>{mappingsView.mockIds.length} mapping folders</span>
            <span className="panel-status">{refreshing ? "Updating..." : mappingsStatusError ? "Listing issue" : "Manual refresh"}</span>
          </div>
          {mappingsStatusError ? <p className="notice warning">{mappingsStatusError}</p> : null}
          {loadingMappings ? <p className="state">Loading mappings...</p> : null}
          {!loadingMappings && mappingsView.mockIds.length === 0 ? <p className="state">No mapping folders.</p> : null}
          <div className="mock-buttons">
            {mappingsView.mockIds.map((mockId) => (
              <button
                key={mockId}
                className={selectedMappingsMockId === mockId ? "mock-button active" : "mock-button"}
                onClick={() => selectMappingsMock(mockId)}
              >
                <span className="mono">{mockId}</span>
              </button>
            ))}
          </div>
        </aside>

        <section className="panel editor-panel">
          <div className="panel-header">
            <span className="mono">{selectedMappingsMockId ?? "No mock selected"}</span>
            {selectedMappingsMockId ? (
              <button
                className="danger-text-button small-button"
                onClick={requestDeleteMappingFolder}
                disabled={busyMappingFolder === selectedMappingsMockId}
              >
                {busyMappingFolder === selectedMappingsMockId ? "Deleting..." : "Delete folder"}
              </button>
            ) : (
              <span className="panel-status">Stored mapping files</span>
            )}
          </div>
          {loadingMappingsTree ? <p className="state">Loading file tree...</p> : null}
          {!loadingMappingsTree && selectedMappingsMockId && mappingsTree ? (
            <>
              {mappingsTree.children.length > 0 ? (
                <div className="file-tree-toolbar">
                  <button className="secondary-button small-button" type="button" onClick={expandAllMappingFolders}>
                    Expand all
                  </button>
                  <button className="secondary-button small-button" type="button" onClick={collapseAllMappingFolders}>
                    Collapse all
                  </button>
                </div>
              ) : null}
              <div className="file-tree">
                {mappingsTree.children.length > 0
                  ? mappingsTree.children.map((child) => renderFileNode(child, 0))
                  : <p className="state inline-state">No mapping files.</p>}
              </div>
            </>
          ) : null}
          {!loadingMappingsTree && !selectedMappingsMockId ? (
            <p className="state">Select a mock folder to view mapping files.</p>
          ) : null}
        </section>
      </section>
    );
  }

  function renderFileNode(node: MappingFileNode, depth: number) {
    const nodeKey = node.path || node.name;
    const collapsed = node.directory && collapsedMappingPaths.has(nodeKey);
    const hasChildren = node.children.length > 0;
    const rowClassName = node.directory ? "file-row folder-row" : "file-row";

    return (
      <div key={nodeKey} className="file-node">
        <div
          className={rowClassName}
          style={{ paddingLeft: `${depth * 18 + 12}px` }}
          role={node.directory ? "button" : undefined}
          tabIndex={node.directory ? 0 : undefined}
          aria-expanded={node.directory ? !collapsed : undefined}
          onClick={node.directory ? () => toggleMappingFolder(nodeKey) : undefined}
          onKeyDown={node.directory ? (event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              toggleMappingFolder(nodeKey);
            }
          } : undefined}
        >
          <span className={node.directory ? "file-icon folder" : "file-icon file"} aria-hidden="true">
            {node.directory ? (collapsed ? ">" : "v") : "-"}
          </span>
          <span className="mono file-name">{node.name}</span>
          {node.directory ? (
            <span className="file-count">{folderItemLabel(node.children.length)}</span>
          ) : null}
          {!node.directory ? (
            <span className="file-actions">
              <button className="secondary-button small-button" onClick={() => openMappingFile(node)}>Open</button>
              <button
                className="danger-text-button small-button"
                onClick={() => requestDeleteMappingFile(node)}
                disabled={busyMappingPath === node.path}
              >
                {busyMappingPath === node.path ? "Deleting..." : "Delete"}
              </button>
            </span>
          ) : null}
        </div>
        {node.directory && hasChildren && !collapsed ? (
          <div>{node.children.map((child) => renderFileNode(child, depth + 1))}</div>
        ) : null}
      </div>
    );
  }

  function renderOptionControl(option: OptionDefinition) {
    if (option.kind === "flag") {
      const inheritedFromBaseline = selectedMock ? hasOption(selectedMock.baseline.options, option.name) : false;
      return (
        <div className="option-row" key={option.name}>
          <label
            className="check-row"
            title={inheritedFromBaseline ? "Inherited from default config." : undefined}
          >
            <input
              type="checkbox"
              checked={Boolean(draft.flags[option.name])}
              disabled={inheritedFromBaseline}
              aria-label={inheritedFromBaseline
                ? `${option.label} is inherited from default config`
                : option.label}
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
            {confirmDialog.secondaryLabel && confirmDialog.onSecondary ? (
              <button
                className="secondary-button"
                onClick={() => void confirmSecondaryAction()}
                disabled={confirming}
              >
                {confirming ? "Working..." : confirmDialog.secondaryLabel}
              </button>
            ) : null}
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

function normalizeConfigView(configView: ConfigView & { routing?: RoutingView }): ConfigView {
  return {
    ...configView,
    routing: configView.routing ?? { mode: "HOST", host: window.location.hostname }
  };
}

function mockBaseUrl(mockId: string, routing: RoutingView) {
  const origin = window.location.origin;
  if (routing.mode === "PATH") {
    return `${origin}/${encodeURIComponent(mockId)}`;
  }

  const protocol = window.location.protocol;
  const host = hostWithBrowserPort(routing.host);
  return `${protocol}//${mockId}.${host}`;
}

function hostWithBrowserPort(configuredHost: string) {
  const host = configuredHost.trim() || window.location.hostname;
  if (host.includes(":") || !window.location.port) {
    return host;
  }
  return `${host}:${window.location.port}`;
}

function optionGroupNames(options: OptionDefinition[]) {
  return [...groupOptions(options).map(([group]) => group), RESOURCE_GROUP_NAME];
}

function tabFromHash(): Tab {
  if (window.location.hash === "#config") {
    return "config";
  }
  if (window.location.hash === "#mappings") {
    return "mappings";
  }
  return "mocks";
}

function tabHash(tab: Tab) {
  if (tab === "config") {
    return "#config";
  }
  if (tab === "mappings") {
    return "#mappings";
  }
  return "#active";
}

function tabTitle(tab: Tab) {
  if (tab === "config") {
    return "Configuration";
  }
  if (tab === "mappings") {
    return "Persisted Mappings";
  }
  return "Active Mocks";
}

function tabSubtitle(tab: Tab, mappingsEnabled: boolean) {
  if (tab === "config") {
    return "Edit per-mock startup options.";
  }
  if (tab === "mappings") {
    return mappingsEnabled
      ? "Inspect persisted mock mapping files."
      : "Enable persistent mappings storage to inspect stored WireMock mapping files.";
  }
  return "Inspect currently active mocks.";
}

function mappingsTabTitle(loaded: boolean, enabled: boolean, error: string | null) {
  if (error) {
    return "Mappings status could not be loaded";
  }
  if (!loaded) {
    return "Checking persistent mappings storage";
  }
  return enabled ? undefined : "Enable persistent mappings storage to use this view";
}

function mappingFileUrl(mockId: string, path: string) {
  const params = new URLSearchParams({ path });
  return `${MAPPINGS_API_PATH}/${encodeURIComponent(mockId)}/files?${params.toString()}`;
}

function mappingFolderUrl(mockId: string) {
  return `${MAPPINGS_API_PATH}/${encodeURIComponent(mockId)}`;
}

function collectDirectoryPaths(node: MappingFileNode) {
  const paths = new Set<string>();

  function visit(current: MappingFileNode) {
    if (current.directory && current.path) {
      paths.add(current.path);
    }
    current.children.forEach(visit);
  }

  node.children.forEach(visit);
  return paths;
}

function folderItemLabel(count: number) {
  if (count === 0) {
    return "empty";
  }
  return `${count} ${count === 1 ? "item" : "items"}`;
}

async function errorMessage(response: Response, fallback: string) {
  if (response.status === 409) {
    return "Config changed. Refresh and retry.";
  }
  const body = await response.text();
  return body.trim() || fallback;
}
