import React from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { WebSocketProvider, useWebSocket } from "./WebSocketContext";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { getSessionExpiredEventName, saveSession, getStoredSession } from "../services/authService";

jest.mock("@stomp/stompjs", () => ({ Client: jest.fn() }));
jest.mock("sockjs-client", () => jest.fn());
let client;
let callbacks;
let subscriptions;
function Consumer() {
  const socket = useWebSocket();
  return <>
    <span>{socket.connected ? "Online" : "Offline"}</span>
    <span data-testid="presence">{JSON.stringify(socket.presenceMessage)}</span>
    <button onClick={() => socket.subscribeToDocument(1)}>Open A</button>
    <button onClick={() => socket.subscribeToDocument(2)}>Open B</button>
    <button onClick={() => socket.sendPresenceTyping(1)}>Type</button>
    <button onClick={socket.unsubscribeFromDocument}>Leave</button>
  </>;
}
beforeEach(() => {
  jest.useFakeTimers();
  jest.clearAllMocks();
  subscriptions = [];
  client = { connected: true, activate: jest.fn(), deactivate: jest.fn(), publish: jest.fn(),
    subscribe: jest.fn((destination, receive) => {
      const sub = { destination, receive, unsubscribe: jest.fn() };
      subscriptions.push(sub);
      return sub;
    }),
  };
  Client.mockImplementation((options) => { callbacks = options; return client; });
});
afterEach(() => jest.useRealTimers());
const tokenFor = (seconds = 3600) => `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))}.signature`;
function connect(token = tokenFor()) {
  render(<WebSocketProvider token={token}><Consumer /></WebSocketProvider>);
  act(() => callbacks.onConnect());
  fireEvent.click(screen.getByText("Open A"));
}
const sent = (destination) => client.publish.mock.calls.filter(([frame]) => frame.destination === destination);

test("leaves the old document on switch and stops timers after leaving", () => {
  connect();
  const oldPresence = subscriptions.find((sub) => sub.destination === "/topic/documents/1/presence");
  fireEvent.click(screen.getByText("Open B"));
  expect(sent("/app/documents/1/presence/leave")).toHaveLength(1);
  expect(oldPresence.unsubscribe).toHaveBeenCalled();
  act(() => oldPresence.receive({ body: JSON.stringify({ documentId: 1, activeUsers: [] }) }));
  expect(screen.getByTestId("presence")).toHaveTextContent("null");
  act(() => jest.advanceTimersByTime(15000));
  expect(sent("/app/documents/2/presence/heartbeat")).toHaveLength(1);
  expect(sent("/app/documents/1/presence/heartbeat")).toHaveLength(0);
  fireEvent.click(screen.getByText("Leave"));
  const count = client.publish.mock.calls.length;
  act(() => jest.advanceTimersByTime(45000));
  expect(client.publish).toHaveBeenCalledTimes(count);
});

test("throttles typing and signals viewing after the idle delay", () => {
  connect();
  fireEvent.click(screen.getByText("Type"));
  fireEvent.click(screen.getByText("Type"));
  expect(sent("/app/documents/1/presence/typing")).toHaveLength(1);
  act(() => jest.advanceTimersByTime(1000));
  fireEvent.click(screen.getByText("Type"));
  expect(sent("/app/documents/1/presence/typing")).toHaveLength(2);
  act(() => jest.advanceTimersByTime(1999));
  expect(sent("/app/documents/1/presence/viewing")).toHaveLength(0);
  act(() => jest.advanceTimersByTime(1));
  expect(sent("/app/documents/1/presence/viewing")).toHaveLength(1);
});

test("clears stale presence on transport loss and creates a fresh socket on retry", () => {
  connect();
  const presence = subscriptions.find((sub) => sub.destination === "/topic/documents/1/presence");
  act(() => presence.receive({ body: JSON.stringify({ documentId: 1, activeUsers: [{ name: "Alex" }] }) }));
  expect(screen.getByTestId("presence")).toHaveTextContent("Alex");
  act(() => callbacks.onWebSocketClose());
  expect(screen.getByText("Offline")).toBeInTheDocument();
  expect(screen.getByTestId("presence")).toHaveTextContent("null");
  callbacks.webSocketFactory();
  callbacks.webSocketFactory();
  expect(SockJS).toHaveBeenCalledTimes(2);
  const count = client.publish.mock.calls.length;
  act(() => jest.advanceTimersByTime(45000));
  expect(client.publish).toHaveBeenCalledTimes(count);
  act(() => callbacks.onConnect());
  fireEvent.click(screen.getByText("Open A"));
  expect(sent("/app/documents/1/presence/join")).toHaveLength(2);
});

test("expires an idle connection, clears presence, and stops automatic reconnect", () => {
  const expired = jest.fn();
  window.addEventListener(getSessionExpiredEventName(), expired);
  try {
    const token = tokenFor(10);
    saveSession({ token });
    connect(token);
    act(() => jest.advanceTimersByTime(10000));
    expect(screen.getByText("Offline")).toBeInTheDocument();
    expect(client.reconnectDelay).toBe(0);
    expect(client.deactivate).toHaveBeenCalledWith({ force: true });
    expect(expired).toHaveBeenCalledTimes(1);
    expect(getStoredSession()).toBeNull();
    const count = client.publish.mock.calls.length;
    act(() => jest.advanceTimersByTime(60000));
    expect(client.publish).toHaveBeenCalledTimes(count);
    act(() => callbacks.onWebSocketClose({ code: 4001 }));
    expect(expired).toHaveBeenCalledTimes(1);
  } finally {
    window.removeEventListener(getSessionExpiredEventName(), expired);
  }
});

test.each(["broken-token", "header.e30.signature", tokenFor(-10)])(
  "does not connect using malformed, missing-expiry or expired tokens: %s", (token) => {
    const expired = jest.fn();
    window.addEventListener(getSessionExpiredEventName(), expired);
    try {
      render(<WebSocketProvider token={token}><Consumer /></WebSocketProvider>);
      expect(Client).not.toHaveBeenCalled();
      expect(expired).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener(getSessionExpiredEventName(), expired);
    }
  }
);

test("server expiry closes the session even when the browser clock is behind", () => {
  connect();
  act(() => callbacks.onWebSocketClose({ code: 4001 }));
  expect(screen.getByText("Offline")).toBeInTheDocument();
  expect(client.deactivate).toHaveBeenCalledWith({ force: true });
  expect(client.reconnectDelay).toBe(0);
});

test("reconnecting does not restart the token lifetime", () => {
  connect(tokenFor(30));
  act(() => jest.advanceTimersByTime(20000));
  act(() => callbacks.onWebSocketClose({ code: 1006 }));
  act(() => callbacks.beforeConnect());
  act(() => callbacks.onConnect());
  act(() => jest.advanceTimersByTime(10000));
  expect(screen.getByText("Offline")).toBeInTheDocument();
  expect(client.deactivate).toHaveBeenCalledWith({ force: true });
});

test("checks the deadline on reconnect even if browser timers were suspended", () => {
  connect(tokenFor(30));
  jest.setSystemTime(Date.now() + 31000);
  act(() => callbacks.beforeConnect());
  expect(client.deactivate).toHaveBeenCalledWith({ force: true });
});

test("handles long token lifetimes without overflowing the browser timeout", () => {
  render(<WebSocketProvider token={tokenFor(30 * 86400)}><Consumer /></WebSocketProvider>);
  act(() => jest.advanceTimersByTime(2147483647));
  expect(client.deactivate).not.toHaveBeenCalled();
  act(() => jest.advanceTimersByTime(6 * 86400000));
  expect(client.deactivate).toHaveBeenCalledWith({ force: true });
});

test("unmount clears the expiry timer and ignores callbacks from the old client", () => {
  const expired = jest.fn();
  window.addEventListener(getSessionExpiredEventName(), expired);
  try {
    const { unmount } = render(<WebSocketProvider token={tokenFor(60)}><Consumer /></WebSocketProvider>);
    unmount();
    act(() => callbacks.onWebSocketClose({ code: 4001 }));
    act(() => jest.advanceTimersByTime(120000));
    expect(expired).not.toHaveBeenCalled();
  } finally {
    window.removeEventListener(getSessionExpiredEventName(), expired);
  }
});
