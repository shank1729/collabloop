import { act, fireEvent, render, screen } from "@testing-library/react";
import App from "./App";
import { getStoredSession, getSessionExpiredEventName, clearSession } from "./services/authService";

jest.mock("./components/DocumentEditor", () => () => <div>Document editor</div>);
jest.mock("./context/WebSocketContext", () => ({ WebSocketProvider: ({ children }) => children }));
jest.mock("./services/authService", () => ({
  getStoredSession: jest.fn(() => null),
  getSessionExpiredEventName: jest.fn(() => "collab-session-expired"),
  saveSession: jest.fn(),
  clearSession: jest.fn(),
}));

beforeEach(() => {
  window.history.replaceState(null, "", "/");
  getStoredSession.mockReturnValue(null);
  getSessionExpiredEventName.mockReturnValue("collab-session-expired");
});

test("opens on the product page and supports Back and Forward between entry screens", async () => {
  render(<App />);
  expect(screen.getByRole("heading", { name: /write together/i })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Get started" }));
  expect(screen.getByRole("heading", { name: "Create your account" })).toBeInTheDocument();
  window.history.back();
  expect(await screen.findByRole("heading", { name: /write together/i })).toBeInTheDocument();
  window.history.forward();
  expect(await screen.findByRole("heading", { name: "Create your account" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Back to home" }));
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(screen.getByRole("heading", { name: "Sign in to your workspace" })).toBeInTheDocument();
});

test("returns to sign-in with a clear explanation when the socket reports expiry", () => {
  getStoredSession.mockReturnValueOnce({ token: "session-token" });
  render(<App />);
  fireEvent.click(screen.getByRole("button", { name: "Open workspace" }));
  expect(screen.getByText("Document editor")).toBeInTheDocument();
  act(() => window.dispatchEvent(new CustomEvent("collab-session-expired")));
  expect(screen.getByText(/Your session expired. Please sign in again/)).toBeInTheDocument();
  expect(clearSession).toHaveBeenCalled();
});

test("ignores an old socket's expiry event after a newer login", () => {
  getStoredSession.mockReturnValueOnce({ token: "new-token" });
  render(<App />);
  fireEvent.click(screen.getByRole("button", { name: "Open workspace" }));
  act(() => window.dispatchEvent(new CustomEvent("collab-session-expired", { detail: { token: "old-token" } })));
  expect(screen.getByText("Document editor")).toBeInTheDocument();
  expect(clearSession).not.toHaveBeenCalled();
});
