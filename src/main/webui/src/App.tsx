import { useEffect, useRef, useState } from "react";
import refreshIcon from "./assets/refresh.svg";
import trashIcon from "./assets/trash.svg";

type MockRow = {
  mockId: string;
  podName: string;
};

const API_PATH = "/__fleet/api/mocks";

export default function App() {
  const [rows, setRows] = useState<MockRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyMockId, setBusyMockId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const mountedRef = useRef(true);
  const toastTimerRef = useRef<number | null>(null);

  async function loadMocks(showSpinner: boolean) {
    if (showSpinner) {
      setLoading(true);
    } else {
      setRefreshing(true);
    }

    try {
      const response = await fetch(API_PATH);
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
        setLoading(false);
        setRefreshing(false);
      }
    }
  }

  async function killMock(mockId: string) {
    setBusyMockId(mockId);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(`${API_PATH}/${encodeURIComponent(mockId)}`, {
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
    void loadMocks(true);

    const intervalId = window.setInterval(() => {
      void loadMocks(false);
    }, 5000);

    return () => {
      mountedRef.current = false;
      if (toastTimerRef.current !== null) {
        window.clearTimeout(toastTimerRef.current);
      }
      window.clearInterval(intervalId);
    };
  }, []);

  return (
    <main className="shell">
      {message ? <div className="toast success">{message}</div> : null}

      <section className="hero">
        <p className="eyebrow">Mock Fleet</p>
        <div className="hero-row">
          <div>
            <h1>Active Mocks</h1>
            <p className="subtitle">
              Inspect currently active mocks and remove them before inactivity cleanup runs.
            </p>
          </div>
          <button
            className="refresh-button"
            onClick={() => void loadMocks(false)}
            disabled={loading || refreshing}
            aria-label={loading || refreshing ? "Refreshing" : "Refresh"}
          >
            {loading || refreshing ? (
              <span className="refresh-spinner" aria-hidden="true"></span>
            ) : (
              <img src={refreshIcon} alt="" aria-hidden="true" className="refresh-icon" />
            )}
          </button>
        </div>
      </section>

      {error ? <p className="notice error">{error}</p> : null}

      <section className="panel">
        <div className="panel-header">
          <span>{rows.length} active mocks</span>
          <span className="panel-status">{refreshing ? "Updating..." : "Auto-refresh every 5s"}</span>
        </div>

        {loading ? <p className="state">Loading active mocks...</p> : null}
        {!loading && rows.length === 0 ? <p className="state">No active mocks.</p> : null}

        {!loading && rows.length > 0 ? (
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
                      onClick={() => void killMock(row.mockId)}
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
    </main>
  );
}
