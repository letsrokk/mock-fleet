import { useEffect, useState } from "react";

type MockRow = {
  mockId: string;
  podName: string;
};

const API_PATH = "/__fleet/api/mocks";

export default function App() {
  const [rows, setRows] = useState<MockRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyMockId, setBusyMockId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function loadMocks() {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(API_PATH);
      if (!response.ok) {
        throw new Error(`Unable to load mocks (${response.status})`);
      }
      const data = (await response.json()) as MockRow[];
      setRows(data);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Unable to load mocks.");
    } finally {
      setLoading(false);
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
      setMessage(`Deleted mock '${mockId}'.`);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Unable to delete mock.");
    } finally {
      setBusyMockId(null);
    }
  }

  useEffect(() => {
    void loadMocks();
  }, []);

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">Mock Fleet</p>
        <div className="hero-row">
          <div>
            <h1>Active Mock Pods</h1>
            <p className="subtitle">
              Inspect currently active mocks and remove them before inactivity cleanup runs.
            </p>
          </div>
          <button className="refresh-button" onClick={() => void loadMocks()} disabled={loading}>
            {loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
      </section>

      {message ? <p className="notice success">{message}</p> : null}
      {error ? <p className="notice error">{error}</p> : null}

      <section className="panel">
        <div className="panel-header">
          <span>{rows.length} active mocks</span>
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
                    >
                      {busyMockId === row.mockId ? "Killing..." : "Kill Pod"}
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
