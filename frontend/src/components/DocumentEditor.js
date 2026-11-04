import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import DocumentDashboard from "./DocumentDashboard";
import SharingPanel from "./SharingPanel";
import PresencePanel from "./PresencePanel";
import VersionHistoryPanel from "./VersionHistoryPanel";
import VersionPreview from "./VersionPreview";
import { useWebSocket } from "../context/WebSocketContext";
import {
  createDocument,
  deleteDocument,
  getDocumentById,
  getDocuments,
  patchDocument,
} from "../services/documentService";

const roleLabels = {
  OWNER: "Owner",
  EDITOR: "Editor",
  VIEWER: "Viewer",
};

function normalizeRole(role) {
  const rawRole =
    typeof role === "string"
      ? role
      : role?.name || role?.value || role?.role || "";

  return rawRole.toUpperCase().replace(/^ROLE_/, "");
}

export default function DocumentEditor({ session, onLogout }) {
  const {
    connected,
    lastMessage,
    presenceMessage,
    accessMessage,
    unsubscribeFromDocument,
    sendPresenceTyping,
    sendUpdate,
    subscribeToDocument,
  } = useWebSocket();
  const [documents, setDocuments] = useState([]);
  const [showDashboard, setShowDashboard] = useState(true);
  const [creating, setCreating] = useState(false);
  const [documentLoading, setDocumentLoading] = useState(false);
  const [selectedDocumentId, setSelectedDocumentId] = useState(null);
  const [title, setTitle] = useState("");
  const [titleDraft, setTitleDraft] = useState("");
  const [titleSaving, setTitleSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");
  const [titleStatus, setTitleStatus] = useState("");
  const [content, setContent] = useState("");
  const [updatedAt, setUpdatedAt] = useState("");
  const [collaborators, setCollaborators] = useState([]);
  const [versionNumber, setVersionNumber] = useState(null);
  const [lastEditedByName, setLastEditedByName] = useState("");
  const [previewVersion, setPreviewVersion] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [syncStatus, setSyncStatus] = useState("Loading documents...");
  const [sharingPending, setSharingPending] = useState(false);
  const debounceTimerRef = useRef(null);
  const pendingUpdateRef = useRef(null);
  const handledAccessMessage = useRef(null);
  const mutationPending = titleSaving || deleting || sharingPending;

  const selectedDocument = useMemo(
    () => documents.find((document) => document.id === selectedDocumentId) || null,
    [documents, selectedDocumentId]
  );

  const currentUserRole = useMemo(() => {
    if (!selectedDocument) {
      return "VIEWER";
    }

    if (
      selectedDocument.ownerEmail?.toLowerCase() === session.email?.toLowerCase() ||
      (selectedDocument.ownerId != null && selectedDocument.ownerId === (session.userId ?? session.id))
    ) {
      return "OWNER";
    }

    const collaborator = (selectedDocument.collaborators || []).find(
      (person) => person.email?.toLowerCase() === session.email?.toLowerCase()
    );

    return normalizeRole(collaborator?.role) || "VIEWER";
  }, [selectedDocument, session.email, session.id, session.userId]);

  const canEditDocument = currentUserRole === "OWNER" || currentUserRole === "EDITOR";
  const canManageDocument = currentUserRole === "OWNER";
  const isViewer = currentUserRole === "VIEWER";
  const currentRoleLabel = roleLabels[currentUserRole] || "Viewer";
  const activeUsers = useMemo(() => {
    if (presenceMessage?.documentId !== selectedDocumentId) {
      return [];
    }

    return (presenceMessage.activeUsers || []).filter(
      (user) => user.email?.toLowerCase() !== session.email?.toLowerCase()
    );
  }, [presenceMessage, selectedDocumentId, session.email]);

  const loadWorkspace = useCallback(async () => {
    try {
      setLoading(true);
      setError("");

      const workspaceDocuments = await getDocuments();
      setDocuments(workspaceDocuments);
      setSyncStatus("Workspace loaded");
    } catch (loadError) {
      setError(loadError.message || "Unable to load your documents.");
      setSyncStatus("Could not load workspace");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWorkspace();
  }, [loadWorkspace]);

  useEffect(() => {
    let active = true;
    const loadDocument = async () => {
      if (!selectedDocumentId || showDashboard) {
        return;
      }

      try {
        setDocumentLoading(true);
        const document = await getDocumentById(selectedDocumentId);
        if (!active) return;
        const loadedTitle = document.title || "Untitled document";
        setTitle(loadedTitle);
        setTitleDraft(loadedTitle);
        setTitleStatus("");
        setContent(document.content || "");
        setUpdatedAt(document.updatedAt || document.createdAt || "");
        setCollaborators(document.collaborators || []);
        setVersionNumber(document.version ?? null);
        setLastEditedByName(document.lastEditedByName || "");
        setDocuments((prev) =>
          prev.map((workspaceDocument) =>
            workspaceDocument.id === document.id
              ? { ...workspaceDocument, ...document }
              : workspaceDocument
          )
        );
        setSyncStatus("Document loaded");
      } catch (loadError) {
        if (active) setError(loadError.message || "Unable to load the selected document.");
      } finally {
        if (active) setDocumentLoading(false);
      }
    };

    loadDocument();
    return () => { active = false; };
  }, [selectedDocumentId, showDashboard]);

  useEffect(() => {
    if (!connected || showDashboard || error || !selectedDocumentId) return;
    subscribeToDocument(selectedDocumentId);
    return () => unsubscribeFromDocument();
  }, [connected, showDashboard, error, selectedDocumentId, subscribeToDocument, unsubscribeFromDocument]);

  useEffect(() => {
    if (!accessMessage || handledAccessMessage.current === accessMessage) return;
    handledAccessMessage.current = accessMessage;
    const documentId = accessMessage.documentId;
    if (selectedDocumentId === documentId) {
      clearTimeout(debounceTimerRef.current);
      pendingUpdateRef.current = null;
    }
    if (accessMessage.role == null) {
      setDocuments((prev) => prev.filter((document) => document.id !== documentId));
      if (selectedDocumentId === documentId) {
        unsubscribeFromDocument();
        setContent("");
        setPreviewVersion(null);
        setSelectedDocumentId(null);
        setShowDashboard(true);
        setError("Your access to this document was removed.");
      }
    } else {
      const updatePeople = (people) => (people || []).map((person) =>
        person.email?.toLowerCase() === session.email?.toLowerCase()
          ? { ...person, role: accessMessage.role } : person);
      setDocuments((prev) => prev.map((document) => document.id === documentId
        ? { ...document, collaborators: updatePeople(document.collaborators) } : document));
      if (selectedDocumentId === documentId) setCollaborators(updatePeople);
    }
  }, [accessMessage, selectedDocumentId, session.email, unsubscribeFromDocument]);

  useEffect(() => {
    if (selectedDocumentId && lastMessage && lastMessage.id === selectedDocumentId) {
      setContent(lastMessage.content || "");
      setUpdatedAt(lastMessage.updatedAt || new Date().toISOString());
      setVersionNumber(lastMessage.version ?? versionNumber);
      setDocuments((prev) => prev.map((document) => document.id === lastMessage.id
        ? { ...document, ...lastMessage } : document));
      setSyncStatus(isViewer ? "Read-only access" : "Synced live");
    }
  }, [isViewer, lastMessage, selectedDocumentId, versionNumber]);

  useEffect(() => {
    if (isViewer) {
      setSyncStatus("Read-only access");
    }
  }, [isViewer, selectedDocumentId]);

  useEffect(() => {
    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, []);

  const handleChange = (event) => {
    if (!canEditDocument) {
      setSyncStatus("Read-only access");
      return;
    }

    const nextContent = event.target.value;
    pendingUpdateRef.current = { id: selectedDocumentId, content: nextContent };
    setContent(nextContent);
    setSyncStatus(connected ? "Typing..." : "Offline changes");

    if (connected) {
      sendPresenceTyping(selectedDocumentId);
    }

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }

    debounceTimerRef.current = setTimeout(() => {
      if (pendingUpdateRef.current) sendUpdate(pendingUpdateRef.current);
      pendingUpdateRef.current = null;
      setUpdatedAt(new Date().toISOString());
      setSyncStatus(connected ? "Live update sent" : "Waiting for connection");
    }, 300);
  };

  const flushPendingUpdate = () => {
    clearTimeout(debounceTimerRef.current);
    if (pendingUpdateRef.current) sendUpdate(pendingUpdateRef.current);
    pendingUpdateRef.current = null;
  };

  const handleTitleSubmit = async (event) => {
    event.preventDefault();
    if (mutationPending) return;

    if (!selectedDocumentId || !canEditDocument) {
      setTitleStatus("Only owners and editors can rename this document.");
      return;
    }

    const nextTitle = titleDraft.trim();
    if (!nextTitle) {
      setTitleStatus("Title cannot be empty.");
      return;
    }
    if (nextTitle.length > 255) {
      setTitleStatus("Title must be at most 255 characters.");
      return;
    }

    if (nextTitle === title) {
      setTitleStatus("Title already saved.");
      return;
    }

    try {
      setTitleSaving(true);
      setTitleStatus("Saving title...");
      const updatedDocument = await patchDocument(selectedDocumentId, {
        title: nextTitle,
      });
      setTitle(updatedDocument.title || nextTitle);
      setTitleDraft(updatedDocument.title || nextTitle);
      setUpdatedAt(updatedDocument.updatedAt || new Date().toISOString());
      setVersionNumber(updatedDocument.version ?? versionNumber);
      setLastEditedByName(updatedDocument.lastEditedByName || lastEditedByName);
      setCollaborators(updatedDocument.collaborators || collaborators);
      setDocuments((prev) =>
        prev.map((document) =>
          document.id === updatedDocument.id
            ? { ...document, ...updatedDocument }
            : document
        )
      );
      setTitleStatus("Title saved");
      setSyncStatus("Document renamed");
    } catch (renameError) {
      setTitleStatus(renameError.message || "Unable to rename the document.");
    } finally {
      setTitleSaving(false);
    }
  };

  const handleCreateDocument = async () => {
    if (creating || mutationPending) return;
    try {
      setCreating(true);
      setError("");
      const newDocument = await createDocument({
        title: `Shared Notes ${documents.length + 1}`,
        content: "Start writing here...",
      });
      setDocuments((prev) => [newDocument, ...prev]);
      flushPendingUpdate();
      setSelectedDocumentId(newDocument.id);
      setPreviewVersion(null);
      setDocumentLoading(true);
      setShowDashboard(false);
      setSyncStatus("New document created");
    } catch (createError) {
      setError(createError.message || "Unable to create a new document.");
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteDocument = async () => {
    if (mutationPending) return;
    if (!selectedDocumentId || !canManageDocument) {
      setError("Only the document owner can delete this document.");
      return;
    }

    const confirmed = window.confirm(
      `Delete "${title}"? This will permanently delete the document and its version history.`
    );

    if (!confirmed) {
      return;
    }

    try {
      setDeleting(true);
      setDeleteError("");
      await deleteDocument(selectedDocumentId);
      clearTimeout(debounceTimerRef.current);
      pendingUpdateRef.current = null;

      const remainingDocuments = documents.filter(
        (document) => document.id !== selectedDocumentId
      );

      setDocuments(remainingDocuments);
      setSelectedDocumentId(null);
      setShowDashboard(true);

      setSyncStatus("Document deleted");
    } catch (deleteError) {
      setDeleteError(deleteError.message || "Unable to delete the document. Please try again.");
    } finally {
      setDeleting(false);
    }
  };

  const formattedUpdatedAt = updatedAt
    ? new Date(updatedAt).toLocaleString()
    : "Not available";

  const openDocument = (id) => {
    if (mutationPending) return;
    flushPendingUpdate();
    setPreviewVersion(null);
    setDeleteError("");
    setError("");
    setDocumentLoading(true);
    setSelectedDocumentId(id);
    setShowDashboard(false);
  };

  const backToDocuments = () => {
    if (mutationPending) return;
    flushPendingUpdate();
    setPreviewVersion(null);
    setError("");
    setShowDashboard(true);
  };

  if (showDashboard) {
    return <DocumentDashboard documents={documents} session={session} onOpen={openDocument}
      onCreate={handleCreateDocument} onLogout={onLogout} onRefresh={loadWorkspace}
      creating={creating} loading={loading} error={error} />;
  }

  if (documentLoading && !error) {
    return (
      <main className="editor-layout">
        <section className="editor-card">
          <p className="editor-banner" role="status">Loading document...</p>
          <button className="dashboard-secondary" onClick={backToDocuments}>Back to documents</button>
        </section>
      </main>
    );
  }

  if (error) {
    return (
      <main className="editor-layout">
        <section className="editor-card">
          <p className="editor-banner editor-banner-error" role="alert">{error}</p>
          <button className="dashboard-secondary" onClick={backToDocuments}>Back to documents</button>
        </section>
      </main>
    );
  }

  return (
    <main className="workspace-layout">
      <aside className="workspace-sidebar">
        <div className="workspace-sidebar-header">
          <p className="editor-kicker">Shared workspace</p>
          <h2>Your documents</h2>
          <button className="dashboard-secondary" type="button" disabled={mutationPending} onClick={backToDocuments}>All documents</button>
          <button className="workspace-button" type="button" disabled={creating || mutationPending} onClick={handleCreateDocument}>
            {creating ? "Creating..." : "New document"}
          </button>
        </div>

        <div className="workspace-list">
          {documents.map((document) => (
            <button
              key={document.id}
              type="button"
              disabled={mutationPending}
              className={
                document.id === selectedDocumentId
                  ? "workspace-list-item workspace-list-item-active"
                  : "workspace-list-item"
              }
              onClick={() => { if (document.id !== selectedDocumentId) openDocument(document.id); }}
            >
              <strong>{document.title}</strong>
              <span>{document.ownerName || "Owner unavailable"}</span>
            </button>
          ))}
        </div>
      </aside>

      <section className="editor-card workspace-editor-card">
        <div className="editor-header">
          <div>
            <p className="editor-kicker">Real-time collaboration</p>
            <form className="document-title-form" onSubmit={handleTitleSubmit}>
              <label className="document-title-label" htmlFor="document-title">
                Document title
              </label>
              <div className="document-title-row">
                <input
                  id="document-title"
                  className="document-title-input"
                  value={titleDraft}
                  onChange={(event) => {
                    setTitleDraft(event.target.value);
                    setTitleStatus("");
                  }}
                  disabled={!canEditDocument || mutationPending}
                  maxLength={255}
                />
                {canEditDocument ? (
                  <button
                    className="workspace-button document-title-button"
                    type="submit"
                    disabled={mutationPending || titleDraft.trim() === title}
                  >
                    {titleSaving ? "Saving" : "Save"}
                  </button>
                ) : null}
              </div>
              {titleStatus ? (
                <p className="document-title-status" role="status">{titleStatus}</p>
              ) : null}
            </form>
            <p className="editor-meta">
              Signed in as {session.name} ({session.email})
            </p>
            <p className="editor-meta">
              Document #{selectedDocumentId} | Last updated {formattedUpdatedAt}
            </p>
            <p className="editor-meta">
              Version {versionNumber ?? "n/a"} | Last edited by {lastEditedByName || "Unknown"}
            </p>
          </div>
          <div className="editor-status-group">
            <span className={`editor-pill editor-pill-role-${currentUserRole.toLowerCase()}`}>
              {currentRoleLabel}
            </span>
            <span
              className={
                connected
                  ? "editor-pill editor-pill-connected"
                  : "editor-pill editor-pill-disconnected"
              }
            >
              {connected ? "Connected" : "Disconnected"}
            </span>
            <span className="editor-pill editor-pill-neutral">{syncStatus}</span>
            {canManageDocument ? (
              <button
                className="editor-pill editor-pill-danger"
                onClick={handleDeleteDocument}
                type="button"
                disabled={mutationPending}
              >
                {deleting ? "Deleting..." : "Delete document"}
              </button>
            ) : null}
            <button
              className="editor-pill editor-pill-action"
              onClick={() => { flushPendingUpdate(); onLogout(); }}
              type="button"
              disabled={mutationPending}
            >
              Sign out
            </button>
          </div>
        </div>

        {deleteError ? <p className="editor-banner editor-banner-error" role="alert">{deleteError}</p> : null}
        <div className="workspace-meta-row">
          <div className="workspace-owner-panel">
            <span className="workspace-section-title">Document owner</span>
            <strong>{selectedDocument?.ownerName || "Unknown"}</strong>
            <span>{selectedDocument?.ownerEmail || "Unknown"}</span>
            {isViewer ? (
              <p className="workspace-readonly-note">
                You can view this document, but editing and sharing are owner-controlled.
              </p>
            ) : null}
          </div>
        </div>
        <SharingPanel key={selectedDocumentId} documentId={selectedDocumentId}
          collaborators={collaborators} canManage={canManageDocument}
          disabled={titleSaving || deleting} onPendingChange={setSharingPending}
          onChange={(document) => {
            setCollaborators(document.collaborators || []);
            setDocuments((prev) => prev.map((item) => item.id === document.id ? { ...item, ...document } : item));
          }} />

        <div className="workspace-editor-grid">
          <div>
            {previewVersion ? <VersionPreview version={previewVersion} onClose={() => setPreviewVersion(null)} /> : <>
            <label className="editor-label" htmlFor="document-content">
              Document content
            </label>
            <textarea
              id="document-content"
              value={content}
              onChange={handleChange}
              disabled={!canEditDocument || deleting}
              rows={14}
              className={canEditDocument ? "editor-textarea" : "editor-textarea editor-textarea-readonly"}
              placeholder={
                canEditDocument
                  ? "Start typing to broadcast live updates..."
                  : "Viewer access is read-only."
              }
            />
            </>}
          </div>

          <aside className="workspace-side-panel">
            <PresencePanel connected={connected} users={activeUsers} />

            <VersionHistoryPanel key={selectedDocumentId} documentId={selectedDocumentId}
              currentVersion={versionNumber} selectedId={previewVersion?.id}
              onSelect={(version) => { flushPendingUpdate(); setPreviewVersion(version); }} />
          </aside>
        </div>
      </section>
    </main>
  );
}
