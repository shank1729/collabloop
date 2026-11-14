import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import DocumentDashboard from "./DocumentDashboard";
import SharingPanel from "./SharingPanel";
import PresencePanel from "./PresencePanel";
import VersionHistoryPanel from "./VersionHistoryPanel";
import VersionPreview from "./VersionPreview";
import DocumentPages from "./DocumentPages";
import DocumentDownloadButton from "./DocumentDownloadButton";
import Whiteboard from "./Whiteboard";
import { useWebSocket } from "../context/WebSocketContext";
import BrandLockup from "./BrandLockup";
import { createDocumentSync } from "../documentSync";
import {
  createDocument,
  deleteDocument,
  getDocumentById,
  getDocuments,
  patchDocument,
  saveDocumentContent,
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

export default function DocumentEditor({ session, onLogout, selectedDocumentId, showWhiteboard, navigate }) {
  const {
    connected,
    lastMessage,
    presenceMessage,
    accessMessage,
    unsubscribeFromDocument,
    sendPresenceTyping,
    subscribeToDocument,
  } = useWebSocket();
  const [documents, setDocuments] = useState([]);
  const [creating, setCreating] = useState(false);
  const [loadedDocumentId, setLoadedDocumentId] = useState(null);
  const [documentError, setDocumentError] = useState("");
  const [title, setTitle] = useState("");
  const [titleDraft, setTitleDraft] = useState("");
  const [titleSaving, setTitleSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");
  const [titleStatus, setTitleStatus] = useState("");
  const [content, setContent] = useState("");
  const [collaborators, setCollaborators] = useState([]);
  const [versionNumber, setVersionNumber] = useState(null);
  const [previewVersion, setPreviewVersion] = useState(null);
  const [maximized, setMaximized] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [sharingPending, setSharingPending] = useState(false);
  const syncRef = useRef(null);
  const [syncStatus, setSyncStatus] = useState({ state: "saved", message: "All changes saved" });
  const handledAccessMessage = useRef(null);
  const maximizeButton = useRef(null);
  const activeDocumentId = useRef();
  const showDashboard = selectedDocumentId == null;
  const documentLoading = !showDashboard && loadedDocumentId !== selectedDocumentId;
  const mutationPending = titleSaving || deleting || sharingPending;

  const flushPendingUpdate = useCallback(() => {
    syncRef.current?.flush();
  }, []);

  useLayoutEffect(() => {
    activeDocumentId.current = selectedDocumentId;
    setLoadedDocumentId(null);
    setDocumentError("");
    setDeleteError("");
    setPreviewVersion(null);
    setMaximized(false);
    // Finish saving the previous document independently of its socket subscription.
    return () => {
      activeDocumentId.current = undefined;
      syncRef.current?.close();
      syncRef.current = null;
    };
  }, [selectedDocumentId, flushPendingUpdate]);

  useEffect(() => {
    if (!maximized || showDashboard || documentError || documentLoading) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const exitOnEscape = (event) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      setMaximized(false);
      maximizeButton.current?.focus();
    };
    document.addEventListener("keydown", exitOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", exitOnEscape);
    };
  }, [maximized, showDashboard, documentError, documentLoading]);

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
    } catch (loadError) {
      setError(loadError.message || "Unable to load your documents.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWorkspace();
  }, [loadWorkspace]);

  useEffect(() => {
    let active = true;
    let sync;
    const loadDocument = async () => {
      if (!selectedDocumentId || showDashboard) {
        return;
      }

      try {
        const document = await getDocumentById(selectedDocumentId);
        if (!active) return;
        const loadedTitle = document.title || "Untitled document";
        setTitle(loadedTitle);
        setTitleDraft(loadedTitle);
        setTitleStatus("");
        sync = createDocumentSync({
          document, save: saveDocumentContent, load: getDocumentById,
          storage: window.sessionStorage,
          storageKey: `collabloop-draft:${session.email}:${document.id}`,
          onChange: (text, snapshot) => {
            if (!active) return;
            setContent(text);
            setVersionNumber(snapshot.version ?? null);
            setDocuments((prev) => prev.map((item) => item.id === snapshot.id
              ? { ...item, ...snapshot } : item));
          },
          onStatus: (status) => { if (active) setSyncStatus(status); },
        });
        syncRef.current = sync;
        setCollaborators(document.collaborators || []);
        setVersionNumber(document.version ?? null);
        setDocuments((prev) =>
          prev.some((item) => item.id === document.id) ? prev.map((workspaceDocument) =>
            workspaceDocument.id === document.id
              ? { ...workspaceDocument, ...document }
              : workspaceDocument
          ) : [...prev, document]
        );
      } catch (loadError) {
        if (active) setDocumentError(loadError.message || "Unable to load the selected document.");
      } finally {
        if (active) setLoadedDocumentId(selectedDocumentId);
      }
    };

    loadDocument();
    return () => {
      active = false;
      sync?.close();
      if (syncRef.current === sync) syncRef.current = null;
    };
  }, [selectedDocumentId, showDashboard, session.email]);

  useEffect(() => {
    if (!connected || showDashboard || documentError || documentLoading) return;
    subscribeToDocument(selectedDocumentId);
    return () => unsubscribeFromDocument();
  }, [connected, showDashboard, documentError, documentLoading, selectedDocumentId, subscribeToDocument, unsubscribeFromDocument]);

  useEffect(() => {
    if (showDashboard || documentLoading || documentError) return;
    const refresh = () => syncRef.current?.refresh();
    refresh();
    const timer = setInterval(refresh, 8000);
    window.addEventListener("online", refresh);
    return () => { clearInterval(timer); window.removeEventListener("online", refresh); };
  }, [connected, showDashboard, documentLoading, documentError, selectedDocumentId]);

  useEffect(() => {
    if (!accessMessage || handledAccessMessage.current === accessMessage) return;
    handledAccessMessage.current = accessMessage;
    const documentId = accessMessage.documentId;
    if (selectedDocumentId === documentId && (accessMessage.role == null || accessMessage.role === "VIEWER")) {
      syncRef.current?.cancel();
    }
    if (accessMessage.role == null) {
      setDocuments((prev) => prev.filter((document) => document.id !== documentId));
      if (selectedDocumentId === documentId) {
        unsubscribeFromDocument();
        setContent("");
        navigate("workspace", { replace: true });
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
  }, [accessMessage, selectedDocumentId, session.email, unsubscribeFromDocument, navigate]);

  useEffect(() => {
    if (selectedDocumentId && lastMessage && lastMessage.id === selectedDocumentId) {
      syncRef.current?.receive(lastMessage);
    }
  }, [lastMessage, selectedDocumentId]);

  const handleChange = (nextContent) => {
    if (!canEditDocument) {
      return;
    }

    syncRef.current?.edit(nextContent);
    setContent(nextContent);

    if (connected) {
      sendPresenceTyping(selectedDocumentId);
    }

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
      setDocuments((prev) => prev.map((document) => document.id === updatedDocument.id
        ? { ...document, ...updatedDocument } : document));
      if (activeDocumentId.current !== selectedDocumentId) return;
      setTitle(updatedDocument.title || nextTitle);
      setTitleDraft(updatedDocument.title || nextTitle);
      setVersionNumber(updatedDocument.version ?? versionNumber);
      syncRef.current?.receive(updatedDocument);
      setCollaborators(updatedDocument.collaborators || collaborators);
      setTitleStatus("Title saved");
    } catch (renameError) {
      if (activeDocumentId.current === selectedDocumentId) {
        setTitleStatus(renameError.message || "Unable to rename the document.");
      }
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
      if (activeDocumentId.current !== null) return;
      navigate("workspace", { documentId: newDocument.id });
    } catch (createError) {
      setError(createError.message || "Unable to create a new document.");
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteDocument = async () => {
    if (mutationPending) return;
    if (!selectedDocumentId || !canManageDocument) {
      setDocumentError("Only the document owner can delete this document.");
      return;
    }

    const confirmed = window.confirm(
      `Delete "${title}"? This will permanently delete the document, its version history, and its whiteboard.`
    );

    if (!confirmed) {
      return;
    }

    try {
      setDeleting(true);
      setDeleteError("");
      await deleteDocument(selectedDocumentId);
      setDocuments((prev) => prev.filter((document) => document.id !== selectedDocumentId));
      if (activeDocumentId.current !== selectedDocumentId) return;
      syncRef.current?.cancel();

      navigate("workspace", { replace: true });

    } catch (deleteError) {
      if (activeDocumentId.current === selectedDocumentId) {
        setDeleteError(deleteError.message || "Unable to delete the document. Please try again.");
      }
    } finally {
      setDeleting(false);
    }
  };

  const openDocument = (id) => {
    if (mutationPending) return;
    setError("");
    navigate("workspace", { documentId: id });
  };

  const backToDocuments = () => {
    if (mutationPending) return;
    setError("");
    navigate("workspace");
  };

  const allDocumentsButton = (
    <button className="workspace-button workspace-back-button" type="button"
      disabled={mutationPending} onClick={backToDocuments}>
      <span aria-hidden="true">←</span> All documents
    </button>
  );

  if (showDashboard) {
    return <DocumentDashboard documents={documents} session={session} onOpen={openDocument}
      onCreate={handleCreateDocument} onLogout={onLogout} onRefresh={loadWorkspace}
      creating={creating} loading={loading} error={error} />;
  }

  if (documentLoading && !documentError) {
    return (
      <main className="editor-layout">
        <section className="editor-card">
          <BrandLockup />
          <p className="editor-banner" role="status">Loading document...</p>
          <button className="workspace-button" type="button" onClick={backToDocuments}>Back to documents</button>
        </section>
      </main>
    );
  }

  if (documentError) {
    return (
      <main className="editor-layout">
        <section className="editor-card">
          <BrandLockup />
          <p className="editor-banner editor-banner-error" role="alert">{documentError}</p>
          <button className="workspace-button" type="button" onClick={backToDocuments}>Back to documents</button>
        </section>
      </main>
    );
  }

  return (
    <main className="workspace-layout">
      <section className="editor-card workspace-editor-card">
        <nav className="workspace-nav" aria-label="Workspace navigation" inert={maximized} aria-hidden={maximized || undefined}>
          <BrandLockup />
          {allDocumentsButton}
        </nav>
        <div inert={maximized}>
          <div className="editor-header">
            <div>
              <form className="document-title-form" onSubmit={handleTitleSubmit}>
                <div className="document-title-row">
                  <input
                    aria-label="Document title"
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
          <details className="workspace-sharing-details" key={selectedDocumentId}>
            <summary>Sharing and access</summary>
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
          </details>
        </div>

        <div className="workspace-editor-grid">
          <section className={maximized ? "workspace-surface workspace-surface-maximized" : "workspace-surface"}
            aria-label={showWhiteboard ? "Whiteboard workspace" : "Document workspace"}>
            <div className="document-view-toggle" role="group" aria-label="Document view">
              {maximized && (
                <div className="workspace-maximized-nav">
                  <BrandLockup />
                  {allDocumentsButton}
                </div>
              )}
              <button type="button" className="dashboard-secondary" aria-pressed={!showWhiteboard}
                onClick={() => navigate("workspace", { documentId: selectedDocumentId })}>Document</button>
              <button type="button" className="dashboard-secondary" aria-pressed={showWhiteboard}
                onClick={() => {
                  flushPendingUpdate();
                  setPreviewVersion(null);
                  navigate("workspace", { documentId: selectedDocumentId, whiteboard: true });
                }}>Whiteboard</button>
              {!showWhiteboard && <DocumentDownloadButton
                title={title}
                content={previewVersion ? previewVersion.contentSnapshot ?? "" : content}
                versionId={previewVersion?.id} />}
              <button type="button" ref={maximizeButton} className="dashboard-secondary workspace-maximize-button"
                aria-pressed={maximized} onClick={() => setMaximized((value) => !value)}>
                <span aria-hidden="true">{maximized ? "↙" : "⤢"}</span>{" "}
                {maximized ? "Restore view" : "Maximize"}
              </button>
            </div>
            {!showWhiteboard && canEditDocument && (
              <p className="document-sync-status" role="status">
                {syncStatus.message}{syncStatus.state === "error" && (
                  <> <button type="button" onClick={() => syncRef.current?.retry()}>Retry saving</button></>
                )}
              </p>
            )}
            {showWhiteboard ? (
              <Whiteboard key={selectedDocumentId} documentId={selectedDocumentId}
                userId={session.userId ?? session.id} canEdit={canEditDocument && !deleting} />
            ) : previewVersion ? (
              <VersionPreview version={previewVersion} onClose={() => setPreviewVersion(null)} />
            ) : (
              <DocumentPages key={selectedDocumentId} content={content} onChange={handleChange}
                readOnly={!canEditDocument || deleting} />
            )}
          </section>

          <aside className="workspace-side-panel" inert={maximized}>
            <PresencePanel connected={connected} users={activeUsers} />

            <VersionHistoryPanel key={selectedDocumentId} documentId={selectedDocumentId}
              currentVersion={versionNumber} selectedId={previewVersion?.id}
              onSelect={(version) => {
                flushPendingUpdate();
                navigate("workspace", { documentId: selectedDocumentId });
                setPreviewVersion(version);
              }} />
          </aside>
        </div>
      </section>
    </main>
  );
}
