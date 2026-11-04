import React, { useState } from "react";
import { removeCollaborator, shareDocument } from "../services/documentService";

export default function SharingPanel({ documentId, collaborators, canManage, disabled, onChange, onPendingChange }) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("VIEWER");
  const [pending, setPending] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const locked = disabled || Boolean(pending);

  async function updateAccess(key, action, success) {
    if (locked || !canManage) return;
    setPending(key);
    onPendingChange(true);
    setError("");
    setMessage("");
    try {
      onChange(await action());
      setMessage(success);
      if (key === "invite") setEmail("");
    } catch (failure) {
      setError(failure.message || "Unable to update access. Please try again.");
    } finally {
      setPending("");
      onPendingChange(false);
    }
  }

  return <section className="workspace-sharing" aria-label="Document sharing" aria-busy={Boolean(pending)}>
    <h3>Collaborators <span className="sharing-count">{collaborators.length}</span></h3>
    {error && <p className="editor-banner editor-banner-error" role="alert">{error}</p>}
    {message && <p className="sharing-status" role="status">{message}</p>}
    {collaborators.length ? <ul className="sharing-list">
      {collaborators.map((person) => <li key={person.id}>
        <div className="sharing-person"><strong>{person.name || person.email}</strong><span>{person.email}</span></div>
        {canManage ? <div className="sharing-actions">
          <label><span className="sr-only">Role for {person.email}</span>
            <select value={person.role} disabled={locked} onChange={(event) => {
              const nextRole = event.target.value;
              updateAccess(`role-${person.id}`, () => shareDocument(documentId, person.email, nextRole),
                `${person.name || person.email} is now ${nextRole === "EDITOR" ? "an editor" : "a viewer"}.`);
            }}>
              <option value="VIEWER">Viewer</option><option value="EDITOR">Editor</option>
            </select>
          </label>
          <button className="dashboard-secondary" disabled={locked} aria-label={`Remove access for ${person.email}`} onClick={() => {
            if (window.confirm(`Remove ${person.email}'s access to this document?`)) {
              updateAccess(`remove-${person.id}`, () => removeCollaborator(documentId, person.id), `Access removed for ${person.email}.`);
            }
          }}>{pending === `remove-${person.id}` ? "Removing..." : "Remove"}</button>
        </div> : <span className="sharing-role">{person.role === "EDITOR" ? "Editor" : "Viewer"}</span>}
      </li>)}
    </ul> : <p className="workspace-empty-text">No collaborators yet.</p>}
    {canManage ? <form className="sharing-invite" onSubmit={(event) => {
      event.preventDefault();
      const normalizedEmail = email.trim().toLowerCase();
      updateAccess("invite", () => shareDocument(documentId, normalizedEmail, role), `Access updated for ${normalizedEmail}.`);
    }}>
      <label className="auth-field"><span>Collaborator email</span><input type="email" required value={email} disabled={locked} onChange={(event) => setEmail(event.target.value)} placeholder="teammate@example.com" /></label>
      <label className="auth-field"><span>Access</span><select value={role} disabled={locked} onChange={(event) => setRole(event.target.value)}><option value="VIEWER">Viewer</option><option value="EDITOR">Editor</option></select></label>
      <button type="submit" className="workspace-button" disabled={locked}>{pending === "invite" ? "Sharing..." : "Share document"}</button>
    </form> : <p className="workspace-empty-text">Only the owner can manage access.</p>}
  </section>;
}
