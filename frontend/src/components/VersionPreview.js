import React from "react";
import { formatHistoryDate } from "./VersionHistoryPanel";
import DocumentPages from "./DocumentPages";

export default function VersionPreview({ version, onClose }) {
  return <section className="version-preview" aria-label="Saved version preview">
    <div className="history-heading"><h3>Version {version.documentVersionNumber ?? "unknown"}</h3>
      <button className="dashboard-secondary" type="button" onClick={onClose}>Back to live document</button>
    </div>
    <p className="editor-meta">{version.editedByName || version.editedByEmail || "Unknown editor"} | {formatHistoryDate(version.editedAt)}</p>
    {!version.contentSnapshot && <p className="workspace-empty-text">This snapshot is empty.</p>}
    <DocumentPages content={version.contentSnapshot ?? ""} />
  </section>;
}
