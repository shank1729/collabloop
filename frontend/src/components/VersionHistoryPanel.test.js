import { act, fireEvent, render, screen } from "@testing-library/react";
import VersionHistoryPanel from "./VersionHistoryPanel";
import { getDocumentHistory } from "../services/documentService";

jest.mock("../services/documentService", () => ({ getDocumentHistory: jest.fn() }));
const snapshot = { id: 10, documentId: 1, documentVersionNumber: 2, contentSnapshot: "Saved content", editedByName: "Alex", editedAt: "2026-10-31T10:00:00" };
beforeEach(() => jest.clearAllMocks());

test("selects a saved snapshot without modifying it", async () => {
  getDocumentHistory.mockResolvedValue([snapshot]);
  const onSelect = jest.fn();
  render(<VersionHistoryPanel documentId={1} currentVersion={2} selectedId={10} onSelect={onSelect} />);
  const entry = await screen.findByRole("button", { name: /Version 2 Alex/ });
  expect(entry).toHaveAttribute("aria-pressed", "true");
  fireEvent.click(entry);
  expect(onSelect).toHaveBeenCalledWith(snapshot);
});

test("shows an empty state and recovers from a failed refresh", async () => {
  getDocumentHistory.mockRejectedValueOnce(new Error("History unavailable")).mockResolvedValueOnce([]);
  render(<VersionHistoryPanel documentId={1} currentVersion={2} onSelect={jest.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("History unavailable");
  fireEvent.click(screen.getByRole("button", { name: "Refresh history" }));
  expect(await screen.findByText("No saved versions yet.")).toBeInTheDocument();
});

test("marks live changes without repeatedly downloading every snapshot", async () => {
  getDocumentHistory.mockResolvedValue([snapshot]);
  const props = { documentId: 1, onSelect: jest.fn() };
  const { rerender } = render(<VersionHistoryPanel {...props} currentVersion={2} />);
  await screen.findByRole("button", { name: /Version 2 Alex/ });
  rerender(<VersionHistoryPanel {...props} currentVersion={3} />);
  expect(screen.getByText("New changes are available.")).toBeInTheDocument();
  expect(getDocumentHistory).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "Refresh history" }));
  await screen.findByRole("button", { name: /Version 2 Alex/ });
  expect(screen.queryByText("New changes are available.")).not.toBeInTheDocument();
});

test("ignores a late response after switching documents", async () => {
  let resolveOld;
  getDocumentHistory.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
    .mockResolvedValueOnce([]);
  const { rerender } = render(<VersionHistoryPanel documentId={1} currentVersion={2} onSelect={jest.fn()} />);
  const signal = getDocumentHistory.mock.calls[0][1].signal;
  rerender(<VersionHistoryPanel documentId={2} currentVersion={0} onSelect={jest.fn()} />);
  await screen.findByText("No saved versions yet.");
  await act(async () => resolveOld([snapshot]));
  expect(signal.aborted).toBe(true);
  expect(screen.queryByRole("button", { name: /Version 2 Alex/ })).not.toBeInTheDocument();
});

test("aborts a stalled history request and permits retry", async () => {
  jest.useFakeTimers();
  try {
    getDocumentHistory.mockImplementationOnce((id, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
    })).mockResolvedValueOnce([]);
    render(<VersionHistoryPanel documentId={1} currentVersion={2} onSelect={jest.fn()} />);
    await act(async () => jest.advanceTimersByTime(60000));
    expect(screen.getByRole("alert")).toHaveTextContent("60 seconds");
    fireEvent.click(screen.getByRole("button", { name: "Refresh history" }));
    await act(async () => {});
    expect(screen.getByText("No saved versions yet.")).toBeInTheDocument();
  } finally { jest.useRealTimers(); }
});
