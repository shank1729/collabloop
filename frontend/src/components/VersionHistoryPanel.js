import React, { useCallback, useEffect, useRef, useState } from "react";
import { getDocumentHistory } from "../services/documentService";

export function formatHistoryDate(value) {
  const date = value ? new Date(value) : null;
  return date && Number.isFinite(date.getTime()) ? date.toLocaleString() : "Date unavailable";
}

export default function VersionHistoryPanel({ documentId, currentVersion, selectedId, onSelect }) {
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [loadedVersion, setLoadedVersion] = useState(null);
  const requestRef = useRef(null);
  const currentVersionRef = useRef(currentVersion);
  currentVersionRef.current = currentVersion;

  const loadHistory = useCallback(async () => {
    requestRef.current?.controller.abort();
    clearTimeout(requestRef.current?.timer);
    const request = { controller: new AbortController() };
    request.timer = setTimeout(() => request.controller.abort(), 60000);
    requestRef.current = request;
    const requestedVersion = currentVersionRef.current;
    setLoading(true);
    setError("");
    try {
      const entries = await getDocumentHistory(documentId, { signal: request.controller.signal });
      if (requestRef.current !== request) return;
      setHistory(entries);
      setLoadedVersion(requestedVersion);
    } catch (failure) {
      if (requestRef.current !== request) return;
      setError(request.controller.signal.aborted
        ? "History did not respond within 60 seconds. Please try again."
        : failure.message || "Unable to load version history.");
    } finally {
      clearTimeout(request.timer);
      if (requestRef.current === request) setLoading(false);
    }
  }, [documentId]);

  useEffect(() => {
    loadHistory();
    return () => {
      requestRef.current?.controller.abort();
      clearTimeout(requestRef.current?.timer);
      requestRef.current = null;
    };
  }, [loadHistory]);

  const changed = currentVersion != null && loadedVersion != null && currentVersion !== loadedVersion;
  return <section className="workspace-history-card" aria-label="Version history">
    <div className="history-heading"><h3>Version history</h3>
      <button className="dashboard-secondary" type="button" disabled={loading} onClick={loadHistory}>Refresh history</button>
    </div>
    {loading && <p className="workspace-empty-text" role="status">Loading history...</p>}
    {error && <p className="editor-banner editor-banner-error" role="alert">{error}</p>}
    {changed && !loading && <p className="history-update" role="status">New changes are available.</p>}
    {!loading && !error && history.length === 0 && <p className="workspace-empty-text">No saved versions yet.</p>}
    <ol className="history-entries">
      {history.map((entry) => <li key={entry.id}>
        <button type="button" aria-pressed={entry.id === selectedId} onClick={() => onSelect(entry)}>
          <strong>Version {entry.documentVersionNumber ?? "unknown"}</strong>
          <span>{entry.editedByName || entry.editedByEmail || "Unknown editor"}</span>
          <span>{formatHistoryDate(entry.editedAt)}</span>
        </button>
      </li>)}
    </ol>
  </section>;
}
