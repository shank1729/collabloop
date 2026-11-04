import { act, fireEvent, render, screen, within } from "@testing-library/react";
import DocumentEditor from "./DocumentEditor";
import { createDocument, deleteDocument, getDocumentById, getDocumentHistory, getDocuments, patchDocument } from "../services/documentService";

jest.mock("../services/documentService", () => ({
  createDocument: jest.fn(), deleteDocument: jest.fn(), getDocumentById: jest.fn(),
  getDocumentHistory: jest.fn(), getDocuments: jest.fn(), patchDocument: jest.fn(), shareDocument: jest.fn(),
}));
const mockSocket = {
  connected: false, sendPresenceViewing: jest.fn(), subscribeToDocument: jest.fn(),
  sendPresenceTyping: jest.fn(), sendUpdate: jest.fn(),
  unsubscribeFromDocument: jest.fn(), accessMessage: null,
};
jest.mock("../context/WebSocketContext", () => ({ useWebSocket: () => mockSocket }));

const session = { userId: 1, name: "Owner", email: "owner@example.com" };
const alpha = { id: 1, title: "Alpha notes", ownerId: 1, ownerName: "Owner", ownerEmail: session.email,
  createdAt: "2026-01-01T12:00:00", updatedAt: "2026-01-02T12:00:00", collaborators: [] };
const beta = { id: 2, title: "Beta plan", ownerId: 2, ownerName: "Teammate", ownerEmail: "team@example.com",
  createdAt: "2026-02-01T12:00:00", updatedAt: "2026-02-02T12:00:00",
  collaborators: [{ id: 1, name: "Owner", email: session.email, role: "EDITOR" }] };

beforeEach(() => {
  jest.clearAllMocks();
  mockSocket.accessMessage = null;
  mockSocket.connected = false;
  mockSocket.presenceMessage = null;
  mockSocket.lastMessage = null;
  getDocuments.mockResolvedValue([alpha, beta]);
  getDocumentById.mockImplementation(async (id) => id === 1 ? alpha : beta);
  getDocumentHistory.mockResolvedValue([]);
});

test("browses metadata, searches collaborators, filters ownership, and sorts documents", async () => {
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  await screen.findByRole("button", { name: alpha.title });
  expect(getDocumentById).not.toHaveBeenCalled();
  const rows = () => within(screen.getByRole("table")).getAllByRole("row").slice(1);
  expect(rows()[0]).toHaveTextContent(beta.title);
  expect(rows()[0]).toHaveTextContent("1 collaborator");
  expect(rows()[0].querySelectorAll("time")).toHaveLength(2);
  fireEvent.change(screen.getByLabelText("Sort by"), { target: { value: "title" } });
  expect(rows()[0]).toHaveTextContent(alpha.title);
  fireEvent.click(screen.getByRole("button", { name: /owned by me/i }));
  expect(screen.queryByRole("button", { name: beta.title })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: /shared with me/i }));
  expect(screen.queryByRole("button", { name: alpha.title })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search documents"), { target: { value: "owner@example.com" } });
  expect(screen.getByRole("button", { name: beta.title })).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search documents"), { target: { value: "missing" } });
  expect(screen.getByText("No matching documents")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
  expect(rows()).toHaveLength(2);
});

test("does not auto-create in an empty workspace; explicit creation opens the editor", async () => {
  getDocuments.mockResolvedValue([]);
  createDocument.mockResolvedValue(alpha);
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  await screen.findByText("No documents yet");
  expect(createDocument).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Create your first document" }));
  await screen.findByLabelText("Document content");
  expect(createDocument).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "All documents" }));
  expect(screen.getByRole("table")).toHaveTextContent(alpha.title);
});

test("can recover from a list error by refreshing", async () => {
  getDocuments.mockRejectedValueOnce(new Error("Connection lost"));
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Connection lost");
  fireEvent.click(screen.getByRole("button", { name: "Refresh documents" }));
  await screen.findByRole("button", { name: alpha.title });
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("deleting the last document returns to an empty overview without replacing it", async () => {
  getDocuments.mockResolvedValue([alpha]);
  deleteDocument.mockResolvedValue(undefined);
  const confirm = jest.spyOn(window, "confirm").mockReturnValue(true);
  try {
    render(<DocumentEditor session={session} onLogout={jest.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete document" }));
    await screen.findByText("No documents yet");
    expect(deleteDocument).toHaveBeenCalledWith(alpha.id);
    expect(createDocument).not.toHaveBeenCalled();
  } finally { confirm.mockRestore(); }
});

test("ignores an old detail response when another document has been opened", async () => {
  let resolveAlpha;
  getDocumentById.mockImplementation((id) => id === 1
    ? new Promise((resolve) => { resolveAlpha = resolve; }) : Promise.resolve(beta));
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  fireEvent.click(await screen.findByRole("button", { name: "Back to documents" }));
  fireEvent.click(screen.getByRole("button", { name: beta.title }));
  await screen.findByLabelText("Document content");
  expect(screen.getByLabelText("Document title")).toHaveValue(beta.title);
  await act(async () => resolveAlpha(alpha));
  expect(screen.getByLabelText("Document title")).toHaveValue(beta.title);
});

test.each([alpha, beta])("owner/editor can rename $title and see the saved name in the dashboard", async (document) => {
  patchDocument.mockResolvedValue({ ...document, title: "Renamed notes" });
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: document.title }));
  fireEvent.change(await screen.findByLabelText("Document title"), { target: { value: "  Renamed notes  " } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(await screen.findByText("Title saved")).toBeInTheDocument();
  expect(patchDocument).toHaveBeenCalledWith(document.id, { title: "Renamed notes" });
  fireEvent.click(screen.getByRole("button", { name: "All documents" }));
  expect(screen.getByRole("button", { name: "Renamed notes" })).toBeInTheDocument();
});

test("rename locks navigation while pending, then retains the draft on failure for retry", async () => {
  let rejectRename;
  patchDocument.mockImplementationOnce(() => new Promise((resolve, reject) => { rejectRename = reject; }));
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  fireEvent.change(await screen.findByLabelText("Document title"), { target: { value: "New name" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(screen.getByRole("button", { name: "Saving" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "All documents" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Delete document" })).toBeDisabled();
  await act(async () => rejectRename(new Error("Unable to rename. Try again.")));
  expect(screen.getByText("Unable to rename. Try again.")).toHaveAttribute("role", "status");
  expect(screen.getByLabelText("Document title")).toHaveValue("New name");
  expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "All documents" })).toBeEnabled();
});

test("viewer cannot rename and editor cannot delete through the UI", async () => {
  const viewerDocument = { ...beta, collaborators: [{ ...beta.collaborators[0], role: "VIEWER" }] };
  getDocumentById.mockResolvedValue(viewerDocument);
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: beta.title }));
  expect(await screen.findByLabelText("Document title")).toBeDisabled();
  expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Delete document" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "All documents" }));
  getDocumentById.mockResolvedValue(beta);
  fireEvent.click(screen.getByRole("button", { name: beta.title }));
  expect(await screen.findByLabelText("Document title")).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Delete document" })).not.toBeInTheDocument();
});

test("blank rename is rejected before making an API call", async () => {
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  fireEvent.change(await screen.findByLabelText("Document title"), { target: { value: "   " } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(screen.getByText("Title cannot be empty.")).toHaveAttribute("role", "status");
  expect(patchDocument).not.toHaveBeenCalled();
});

test("cancelling deletion keeps the document and makes no delete request", async () => {
  const confirm = jest.spyOn(window, "confirm").mockReturnValue(false);
  try {
    render(<DocumentEditor session={session} onLogout={jest.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete document" }));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining(alpha.title));
    expect(deleteDocument).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Document title")).toHaveValue(alpha.title);
  } finally { confirm.mockRestore(); }
});

test("delete blocks duplicate requests and leaves the editor usable after a failure", async () => {
  const confirm = jest.spyOn(window, "confirm").mockReturnValue(true);
  let rejectDelete;
  deleteDocument.mockImplementationOnce(() => new Promise((resolve, reject) => { rejectDelete = reject; }));
  try {
    render(<DocumentEditor session={session} onLogout={jest.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete document" }));
    const deleting = screen.getByRole("button", { name: "Deleting..." });
    expect(deleting).toBeDisabled();
    fireEvent.click(deleting);
    expect(deleteDocument).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "All documents" })).toBeDisabled();
    await act(async () => rejectDelete(new Error("Deletion failed")));
    expect(screen.getByRole("alert")).toHaveTextContent("Deletion failed");
    expect(screen.getByLabelText("Document content")).toBeEnabled();
    expect(screen.getByRole("button", { name: "Delete document" })).toBeEnabled();
  } finally { confirm.mockRestore(); }
});

test("a live downgrade disables editing and a revocation closes the document", async () => {
  const { rerender } = render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: beta.title }));
  expect(await screen.findByLabelText("Document content")).toBeEnabled();
  mockSocket.accessMessage = { documentId: beta.id, role: "VIEWER" };
  rerender(<DocumentEditor session={session} onLogout={jest.fn()} />);
  expect(screen.getByLabelText("Document content")).toBeDisabled();
  mockSocket.accessMessage = { documentId: beta.id, role: "EDITOR" };
  rerender(<DocumentEditor session={session} onLogout={jest.fn()} />);
  expect(screen.getByLabelText("Document content")).toBeEnabled();
  mockSocket.accessMessage = { documentId: beta.id, role: null };
  rerender(<DocumentEditor session={session} onLogout={jest.fn()} />);
  expect(screen.queryByLabelText("Document content")).not.toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("access to this document was removed");
  expect(mockSocket.unsubscribeFromDocument).toHaveBeenCalled();
  expect(screen.queryByRole("button", { name: beta.title })).not.toBeInTheDocument();
});

test("returning to the dashboard flushes the pending edit and leaves live presence", async () => {
  mockSocket.connected = true;
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  const content = await screen.findByLabelText("Document content");
  fireEvent.change(content, { target: { value: "Latest draft" } });
  fireEvent.click(screen.getByRole("button", { name: "All documents" }));
  expect(mockSocket.sendUpdate).toHaveBeenCalledWith({ id: alpha.id, content: "Latest draft" });
  expect(mockSocket.unsubscribeFromDocument).toHaveBeenCalled();
  expect(screen.getByRole("table")).toBeInTheDocument();
});

test("previews old content without sending edits and returns to the latest live content", async () => {
  getDocumentHistory.mockResolvedValue([{ id: 50, documentId: 1, documentVersionNumber: 0,
    contentSnapshot: "Old text\n  with spaces\n<script>alert('test')</script>", editedByName: "Owner", editedAt: "2026-01-01T12:00:00" }]);
  getDocumentById.mockResolvedValue({ ...alpha, content: "Current live content", version: 1 });
  const { rerender } = render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  await screen.findByLabelText("Document content");
  fireEvent.click(await screen.findByRole("button", { name: /Version 0 Owner/ }));
  expect(screen.queryByLabelText("Document content")).not.toBeInTheDocument();
  const preview = screen.getByLabelText("Saved document content");
  expect(preview.textContent).toBe("Old text\n  with spaces\n<script>alert('test')</script>");
  expect(preview.querySelector("script")).toBeNull();
  mockSocket.lastMessage = { id: 1, content: "A newer live update", version: 2 };
  rerender(<DocumentEditor session={session} onLogout={jest.fn()} />);
  expect(preview).toHaveTextContent("Old text");
  fireEvent.click(screen.getByRole("button", { name: "Back to live document" }));
  expect(screen.getByLabelText("Document content")).toHaveValue("A newer live update");
  expect(patchDocument).not.toHaveBeenCalled();
  expect(mockSocket.sendUpdate).not.toHaveBeenCalled();
  mockSocket.lastMessage = null;
});

test("a history failure does not block editing the live document", async () => {
  getDocumentHistory.mockRejectedValueOnce(new Error("Unable to load history"));
  render(<DocumentEditor session={session} onLogout={jest.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: alpha.title }));
  expect(await screen.findByLabelText("Document content")).toBeEnabled();
  expect(await screen.findByRole("alert")).toHaveTextContent("Unable to load history");
});
