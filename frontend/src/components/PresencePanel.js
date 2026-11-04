import React from "react";

function initials(user) {
  return (user.name || user.email || "?").trim().split(/\s+/).slice(0, 2).map((part) => Array.from(part)[0]).join("").toUpperCase();
}

function colorIndex(user) {
  const key = String(user.userId ?? user.email ?? user.name ?? "");
  return Array.from(key).reduce((hash, character) => (hash * 31 + character.codePointAt(0)) % 4, 0);
}

export default function PresencePanel({ connected, users }) {
  return <section className="workspace-presence-card" aria-label="Live presence">
    <h3>Active now {connected && <span className="presence-count">{users.length} {users.length === 1 ? "other person" : "other people"}</span>}</h3>
    {!connected ? <p className="workspace-empty-text" role="status">Reconnecting. Live presence is unavailable.</p>
      : users.length === 0 ? <p className="workspace-empty-text">No one else is here.</p>
        : <ul className="presence-people">{users.map((user) => {
          const typing = user.status === "TYPING";
          return <li key={user.userId || user.email} className="presence-person">
            <span aria-hidden="true" className={`presence-avatar presence-avatar-${colorIndex(user)}`}>{initials(user)}</span>
            <div><strong>{user.name || user.email}</strong><span>{typing ? "is typing..." : "Viewing"}</span></div>
            {typing && <span aria-hidden="true" className="presence-typing">...</span>}
          </li>;
        })}</ul>}
  </section>;
}
