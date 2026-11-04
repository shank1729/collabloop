import React, { useState } from "react";

function isOwner(document, session) {
  return Boolean(
    (document.ownerEmail && document.ownerEmail.toLowerCase() === session.email?.toLowerCase()) ||
    (document.ownerId != null && document.ownerId === (session.userId ?? session.id))
  );
}

function timestamp(value) {
  const time = value ? new Date(value).getTime() : NaN;
  return Number.isFinite(time) ? time : 0;
}

function DocumentDate({ value }) {
  if (!timestamp(value)) return <span className="dashboard-muted">Unavailable</span>;
  return <time dateTime={value} title={new Date(value).toLocaleString()}>
    {new Date(value).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })}
  </time>;
}

export default function DocumentDashboard({ documents, session, onOpen, onCreate, onLogout, onRefresh, creating, loading, error }) {
  const [query, setQuery] = useState("");
  const [scope, setScope] = useState("all");
  const [sort, setSort] = useState("updated");
  const ownedCount = documents.filter((document) => isOwner(document, session)).length;
  const search = query.trim().toLowerCase();
  const visibleDocuments = documents.filter((document) => {
    const owned = isOwner(document, session);
    if ((scope === "owned" && !owned) || (scope === "shared" && owned)) return false;
    return [document.title, document.ownerName, document.ownerEmail,
      ...(document.collaborators || []).flatMap((person) => [person.name, person.email])]
      .some((value) => value?.toLowerCase().includes(search));
  }).sort((a, b) => {
    if (sort === "title") return (a.title || "").localeCompare(b.title || "") || a.id - b.id;
    const aTime = sort === "created" ? a.createdAt : a.updatedAt || a.createdAt;
    const bTime = sort === "created" ? b.createdAt : b.updatedAt || b.createdAt;
    return timestamp(bTime) - timestamp(aTime) || a.id - b.id;
  });

  return (
    <main className="document-dashboard">
      <header className="dashboard-header">
        <div><p className="editor-kicker">Collab workspace</p><h1>Documents</h1></div>
        <div className="dashboard-actions">
          <span className="dashboard-account">{session.name || session.email}</span>
          <button className="dashboard-secondary" onClick={onLogout}>Sign out</button>
          <button className="workspace-button" disabled={creating || loading} onClick={onCreate}>
            {creating ? "Creating..." : "New document"}
          </button>
        </div>
      </header>

      {error ? <p className="editor-banner editor-banner-error" role="alert">{error}</p> : null}

      <div className="dashboard-toolbar">
        <div className="dashboard-scopes" role="group" aria-label="Document ownership">
          {[["all", "All documents", documents.length], ["owned", "Owned by me", ownedCount],
            ["shared", "Shared with me", documents.length - ownedCount]].map(([value, label, count]) => (
            <button key={value} aria-pressed={scope === value} onClick={() => setScope(value)}>
              {label} <span>{count}</span>
            </button>
          ))}
        </div>
        <div className="dashboard-controls">
          <label>Search documents
            <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Title, owner or collaborator" />
          </label>
          <label>Sort by
            <select value={sort} onChange={(event) => setSort(event.target.value)}>
              <option value="updated">Last edited</option><option value="created">Newest created</option><option value="title">Title A-Z</option>
            </select>
          </label>
          <button className="dashboard-secondary" disabled={loading} onClick={onRefresh}>{loading ? "Refreshing..." : "Refresh documents"}</button>
        </div>
      </div>

      {loading ? <p role="status" className="dashboard-empty">Loading documents...</p> : documents.length === 0 ? (
        <section className="dashboard-empty"><h2>{error ? "Documents unavailable" : "No documents yet"}</h2>
          {!error && <button className="workspace-button" disabled={creating} onClick={onCreate}>Create your first document</button>}
        </section>
      ) : visibleDocuments.length === 0 ? (
        <section className="dashboard-empty"><h2>No matching documents</h2>
          <button className="dashboard-secondary" onClick={() => { setQuery(""); setScope("all"); }}>Clear filters</button>
        </section>
      ) : (
        <>
          <p className="dashboard-result-count" role="status">{visibleDocuments.length} of {documents.length} documents</p>
          <div className="dashboard-table-scroll" role="region" aria-label="Documents" tabIndex={0}>
            <table className="dashboard-table">
              <thead><tr><th scope="col">Document</th><th scope="col">Owner</th><th scope="col">Collaborators</th><th scope="col">Created</th><th scope="col">Last edited</th></tr></thead>
              <tbody>{visibleDocuments.map((document) => {
                const people = document.collaborators || [];
                return <tr key={document.id}>
                  <td><button className="dashboard-document-link" onClick={() => onOpen(document.id)}>{document.title || "Untitled document"}</button>
                    <span className="dashboard-muted">{isOwner(document, session) ? "Owned by you" : "Shared with you"}</span></td>
                  <td><span>{document.ownerName || document.ownerEmail || "Unknown owner"}</span>
                    <span className="dashboard-muted">{document.ownerEmail}</span></td>
                  <td>{people.length ? <><span>{people.length} {people.length === 1 ? "collaborator" : "collaborators"}</span>
                    <span className="dashboard-muted">{people.slice(0, 2).map((person) => person.name || person.email).join(", ")}{people.length > 2 ? ` +${people.length - 2}` : ""}</span></>
                    : <span className="dashboard-muted">No collaborators</span>}</td>
                  <td><DocumentDate value={document.createdAt} /></td>
                  <td><DocumentDate value={document.updatedAt || document.createdAt} />
                    {document.lastEditedByName && <span className="dashboard-muted">{document.lastEditedByName}</span>}</td>
                </tr>;
              })}</tbody>
            </table>
          </div>
        </>
      )}
    </main>
  );
}
