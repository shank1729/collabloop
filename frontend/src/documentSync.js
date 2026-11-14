import { diffChars } from "diff";

function changes(base, value) {
  const diff = diffChars(base, value, { timeout: 150 });
  if (!diff) {
    const error = new Error("These edits need review. Your draft has been kept; please copy it before reloading.");
    error.mergeConflict = true;
    throw error;
  }
  const inserts = new Map();
  const deleted = new Set();
  let position = 0;
  for (const part of diff) {
    if (part.added) inserts.set(position, (inserts.get(position) || "") + part.value);
    else for (const character of part.value) {
      if (part.removed) deleted.add(position);
      position += character.length;
    }
  }
  return { inserts, deleted };
}

// Reapply only the local changes to the shared base. A deletion removes original
// characters, never text that another writer inserted while this user was typing.
export function mergeDocumentText(base, local, remote) {
  if (local === base) return remote;
  if (remote === base || local === remote) return local;
  const left = changes(base, local);
  const right = changes(base, remote);
  const output = [];
  let position = 0;
  for (const character of base) {
    output.push(right.inserts.get(position) || "", left.inserts.get(position) || "");
    if (!left.deleted.has(position) && !right.deleted.has(position)) output.push(character);
    position += character.length;
  }
  output.push(right.inserts.get(position) || "", left.inserts.get(position) || "");
  return output.join("");
}

export function mapTextOffset(before, after, offset) {
  const parts = diffChars(before, after, { timeout: 100 });
  if (!parts) return Math.min(offset, after.length);
  let oldPosition = 0;
  let nextPosition = 0;
  for (const part of parts) {
    const length = part.value.length;
    if (part.added) nextPosition += length;
    else {
      if (oldPosition + length > offset) {
        return nextPosition + (part.removed ? 0 : offset - oldPosition);
      }
      oldPosition += length;
      if (!part.removed) nextPosition += length;
    }
  }
  return nextPosition;
}

function editId() {
  if (window.crypto.randomUUID) return window.crypto.randomUUID();
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function createDocumentSync({ document, save, load, onChange, onStatus, storage, storageKey }) {
  let base = { ...document, content: document.content || "", version: document.version ?? 0 };
  let local = base.content;
  let request = null;
  let buffered = null;
  let busy = false;
  let closed = false;
  let cancelled = false;
  let paused = false;
  let failures = 0;
  let timer;
  const status = (state, message) => { if (!closed) onStatus?.({ state, message }); };
  const changed = () => { if (!closed) onChange(local, base); };
  function persist() {
    try {
      if (local === base.content && !request) storage?.removeItem(storageKey);
      else storage?.setItem(storageKey, JSON.stringify({ base, local, request }));
    } catch { /* The in-memory draft remains intact if browser storage is full. */ }
  }
  function schedule(delay = 300) {
    clearTimeout(timer);
    if (!cancelled && !paused && (request || local !== base.content)) timer = setTimeout(flush, delay);
    else if (!busy && !paused) status("saved", "All changes saved");
  }
  function integrate(snapshot) {
    if (snapshot.id !== base.id || !Number.isSafeInteger(snapshot.version) || snapshot.version <= base.version) return;
    const merged = mergeDocumentText(base.content, local, snapshot.content || "");
    base = { ...snapshot, content: snapshot.content || "" };
    local = merged;
    persist();
    changed();
  }
  function receive(snapshot) {
    if (cancelled || snapshot.id !== base.id || !Number.isSafeInteger(snapshot.version) || snapshot.version <= base.version) return;
    if (busy || request || paused) {
      if (!buffered || snapshot.version > buffered.version) buffered = snapshot;
      return;
    }
    try { integrate(snapshot); schedule(); }
    catch (error) { paused = true; status("error", error.message); }
  }
  function drain() {
    if (buffered) {
      const next = buffered;
      integrate(next);
      buffered = null;
    }
  }
  async function flush() {
    clearTimeout(timer);
    if (busy || cancelled || paused) return;
    if (!request && local === base.content) { status("saved", "All changes saved"); return; }
    busy = true;
    request ||= { content: local, baseVersion: base.version, editId: editId() };
    persist();
    status("saving", "Saving changes…");
    try {
      const saved = await save(base.id, request);
      if (cancelled) return;
      // New typing may have happened after this request started. Its base is the
      // submitted text, so an old acknowledgement must never replace that typing.
      local = mergeDocumentText(request.content, local, saved.content);
      base = saved;
      request = null;
      drain();
      failures = 0;
      persist();
      changed();
    } catch (error) {
      if (cancelled) return;
      if (error.status === 409) {
        try {
          const latest = await load(base.id);
          if (cancelled) return;
          integrate(latest);
          request = null;
          drain();
          failures = 0;
          persist();
        } catch (refreshError) {
          failures++;
          if (refreshError.mergeConflict) paused = true;
          status("error", paused ? refreshError.message : "Changes are kept on this browser. Reconnecting to save…");
        }
      } else {
        failures++;
        if (error.mergeConflict || error.status === 401 || error.status === 403 || error.status === 404) paused = true;
        status("error", error.mergeConflict ? error.message : paused ? "Unable to save. Copy your draft before leaving this page."
          : "Changes are kept on this browser. Reconnecting to save…");
        // Retry the SAME edit ID. The server remembers acknowledgements, even if
        // this reply was lost and another writer has already made more changes.
      }
    } finally {
      busy = false;
      if (!cancelled) schedule(failures ? Math.min(1000 * 2 ** failures, 15000) : 0);
    }
  }
  async function refresh() {
    try { const latest = await load(base.id); if (!closed) receive(latest); }
    catch { /* An unavailable poll never replaces a local draft. Save retries report failures. */ }
  }
  try {
    const draft = JSON.parse(storage?.getItem(storageKey) || "null");
    if (draft?.base?.id === base.id && typeof draft.local === "string") {
      const latest = base;
      base = draft.base;
      local = draft.local;
      request = draft.request;
      if (request) buffered = latest;
      else integrate(latest);
    }
  } catch { /* Ignore invalid stored data without affecting the loaded document. */ }
  changed();
  schedule();
  return {
    edit(value) { local = value; persist(); status("pending", "Changes waiting to save…"); schedule(); },
    receive, refresh, flush,
    retry() {
      paused = false; failures = 0;
      try { if (!request) drain(); }
      catch (error) { paused = true; status("error", error.message); return; }
      flush();
    },
    close() { closed = true; flush(); },
    cancel() { cancelled = true; closed = true; clearTimeout(timer); },
  };
}
