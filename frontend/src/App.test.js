import { act, render, screen } from "@testing-library/react";
import App from "./App";
import { getStoredSession, getSessionExpiredEventName, clearSession } from "./services/authService";

jest.mock("./components/AuthScreen", () => ({ initialMessage }) => <div>Auth screen {initialMessage}</div>);
jest.mock("./components/DocumentEditor", () => () => <div>Document editor</div>);
jest.mock("./context/WebSocketContext", () => ({ WebSocketProvider: ({ children }) => children }));
jest.mock("./services/authService", () => ({
  getStoredSession: jest.fn(() => null),
  getSessionExpiredEventName: jest.fn(() => "collab-session-expired"),
  saveSession: jest.fn(),
  clearSession: jest.fn(),
}));

beforeEach(() => {
  getStoredSession.mockReturnValue(null);
  getSessionExpiredEventName.mockReturnValue("collab-session-expired");
});

test("renders the auth screen when no session exists", () => {
  render(<App />);
  expect(screen.getByText(/auth screen/i)).toBeInTheDocument();
});

test("returns to sign-in with a clear explanation when the socket reports expiry", () => {
  getStoredSession.mockReturnValueOnce({ token: "session-token" });
  render(<App />);
  expect(screen.getByText("Document editor")).toBeInTheDocument();
  act(() => window.dispatchEvent(new CustomEvent("collab-session-expired")));
  expect(screen.getByText(/Your session expired. Please sign in again/)).toBeInTheDocument();
  expect(clearSession).toHaveBeenCalled();
});

test("ignores an old socket's expiry event after a newer login", () => {
  getStoredSession.mockReturnValueOnce({ token: "new-token" });
  render(<App />);
  act(() => window.dispatchEvent(new CustomEvent("collab-session-expired", { detail: { token: "old-token" } })));
  expect(screen.getByText("Document editor")).toBeInTheDocument();
  expect(clearSession).not.toHaveBeenCalled();
});
