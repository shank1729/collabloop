import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { getTokenExpiryMs, notifySessionExpired } from "../services/authService";

const WebSocketContext = createContext(null);
const wsUrl = process.env.REACT_APP_WS_URL || "http://localhost:8080/ws";

function buildAuthenticatedWsUrl(token) {
  if (!token) {
    return wsUrl;
  }

  const separator = wsUrl.includes("?") ? "&" : "?";
  return `${wsUrl}${separator}token=${encodeURIComponent(token)}`;
}

export const WebSocketProvider = ({ children, token }) => {
  const [connected, setConnected] = useState(false);
  const [lastMessage, setLastMessage] = useState(null);
  const [presenceMessage, setPresenceMessage] = useState(null);
  const [accessMessage, setAccessMessage] = useState(null);
  const clientRef = useRef(null);
  const documentSubscriptionRef = useRef(null);
  const presenceSubscriptionRef = useRef(null);
  const accessSubscriptionRef = useRef(null);
  const activeDocumentRef = useRef(null);
  const heartbeatTimerRef = useRef(null);
  const typingTimerRef = useRef(null);
  const lastTypingSentRef = useRef(null);
  const expiryRef = useRef(null);
  const expireSessionRef = useRef(null);

  const publishJson = useCallback((destination, payload = {}) => {
    if (expiryRef.current != null && Date.now() >= expiryRef.current) {
      expireSessionRef.current?.();
      return false;
    }
    if (clientRef.current && clientRef.current.connected) {
      clientRef.current.publish({
        destination,
        body: JSON.stringify(payload),
      });
      return true;
    }
    return false;
  }, []);

  const clearPresenceTimers = useCallback(() => {
    clearInterval(heartbeatTimerRef.current);
    clearTimeout(typingTimerRef.current);
    heartbeatTimerRef.current = null;
    typingTimerRef.current = null;
    lastTypingSentRef.current = null;
  }, []);

  const unsubscribeFromDocument = useCallback(() => {
    const documentId = activeDocumentRef.current;
    if (documentId) publishJson(`/app/documents/${documentId}/presence/leave`);
    clearPresenceTimers();
    if (clientRef.current?.connected) {
      documentSubscriptionRef.current?.unsubscribe();
      presenceSubscriptionRef.current?.unsubscribe();
    }
    activeDocumentRef.current = null;
    documentSubscriptionRef.current = null;
    presenceSubscriptionRef.current = null;
    setLastMessage(null);
    setPresenceMessage(null);
  }, [publishJson, clearPresenceTimers]);

  useEffect(() => {
    if (!token) {
      setConnected(false);
      setPresenceMessage(null);
      return undefined;
    }

    const expiresAt = getTokenExpiryMs(token);
    if (expiresAt == null || Date.now() >= expiresAt) {
      notifySessionExpired(token);
      return undefined;
    }
    expiryRef.current = expiresAt;
    let disposed = false;
    let expired = false;
    let expiryTimer;
    let client;

    const expireSession = () => {
      if (disposed || expired) return;
      expired = true;
      clearTimeout(expiryTimer);
      clearPresenceTimers();
      activeDocumentRef.current = null;
      documentSubscriptionRef.current = null;
      presenceSubscriptionRef.current = null;
      accessSubscriptionRef.current = null;
      setPresenceMessage(null);
      setLastMessage(null);
      setAccessMessage(null);
      setConnected(false);
      client.reconnectDelay = 0;
      client.deactivate({ force: true });
      notifySessionExpired(token);
    };
    expireSessionRef.current = expireSession;

    const checkExpiry = () => {
      if (disposed || expired) return;
      const remaining = expiresAt - Date.now();
      if (remaining <= 0) expireSession();
      // Browser timers overflow beyond about 24.8 days. Recheck long lifetimes in chunks.
      else expiryTimer = setTimeout(checkExpiry, Math.min(remaining, 2147483647));
    };

    client = new Client({
      webSocketFactory: () => new SockJS(buildAuthenticatedWsUrl(token)),
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 5000,
      connectionTimeout: 10000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      beforeConnect: () => {
        if (Date.now() >= expiresAt) expireSession();
      },
      onConnect: () => {
        if (disposed || expired) return;
        if (Date.now() >= expiresAt) { expireSession(); return; }
        accessSubscriptionRef.current = client.subscribe("/user/queue/document-access", (message) => {
          if (!disposed && !expired) setAccessMessage(JSON.parse(message.body));
        });
        setConnected(true);
      },
      onDisconnect: () => {
        if (disposed) return;
        setConnected(false);
      },
      onWebSocketClose: (event) => {
        if (disposed || expired) return;
        if (event?.code === 4001 || Date.now() >= expiresAt) { expireSession(); return; }
        clearPresenceTimers();
        activeDocumentRef.current = null;
        documentSubscriptionRef.current = null;
        presenceSubscriptionRef.current = null;
        accessSubscriptionRef.current = null;
        setPresenceMessage(null);
        setLastMessage(null);
        setConnected(false);
      },
      onStompError: (frame) => {
        if (disposed || expired) return;
        if (Date.now() >= expiresAt) { expireSession(); return; }
        console.error("Broker error:", frame.headers["message"]);
      },
    });

    clientRef.current = client;
    checkExpiry();
    client.activate();

    return () => {
      disposed = true;
      clearTimeout(expiryTimer);
      unsubscribeFromDocument();
      if (client.connected) accessSubscriptionRef.current?.unsubscribe();
      client.deactivate();
      clientRef.current = null;
      expireSessionRef.current = null;
      expiryRef.current = null;
    };
  }, [token, clearPresenceTimers, unsubscribeFromDocument]);

  const subscribeToDocument = useCallback((documentId) => {
    if (expiryRef.current != null && Date.now() >= expiryRef.current) {
      expireSessionRef.current?.();
      return;
    }
    if (!clientRef.current?.connected || !documentId) {
      return;
    }

    if (activeDocumentRef.current === documentId) return;
    unsubscribeFromDocument();
    activeDocumentRef.current = documentId;
    const client = clientRef.current;

    documentSubscriptionRef.current = clientRef.current.subscribe(
      `/topic/documents/${documentId}`,
      (message) => {
        if (clientRef.current !== client || activeDocumentRef.current !== documentId) return;
        const body = JSON.parse(message.body);
        setLastMessage(body);
      }
    );

    presenceSubscriptionRef.current = clientRef.current.subscribe(
      `/topic/documents/${documentId}/presence`,
      (message) => {
        if (clientRef.current !== client || activeDocumentRef.current !== documentId) return;
        const body = JSON.parse(message.body);
        setPresenceMessage(body);
      }
    );

    publishJson(`/app/documents/${documentId}/presence/join`);
    heartbeatTimerRef.current = setInterval(() => {
      publishJson(`/app/documents/${documentId}/presence/heartbeat`);
    }, 15000);
  }, [publishJson, unsubscribeFromDocument]);

  const sendUpdate = useCallback((documentUpdate) => {
    if (documentUpdate.id !== activeDocumentRef.current) return false;
    return publishJson("/app/editDocument", documentUpdate);
  }, [publishJson]);

  const subscribeToWhiteboard = useCallback((documentId, onChange) => {
    const client = clientRef.current;
    if (!client?.connected) return undefined;
    let active = true;
    const subscription = client.subscribe(`/topic/documents/${documentId}/whiteboard`, () => {
      if (active && clientRef.current === client) onChange();
    });
    return () => {
      active = false;
      if (client.connected) subscription.unsubscribe();
    };
  }, []);

  const sendPresenceTyping = useCallback((documentId) => {
    if (documentId !== activeDocumentRef.current || !clientRef.current?.connected) return;
    const now = Date.now();
    if (lastTypingSentRef.current == null || now - lastTypingSentRef.current >= 1000) {
      publishJson(`/app/documents/${documentId}/presence/typing`);
      lastTypingSentRef.current = now;
    }
    clearTimeout(typingTimerRef.current);
    typingTimerRef.current = setTimeout(() => {
      if (activeDocumentRef.current === documentId) {
        publishJson(`/app/documents/${documentId}/presence/viewing`);
        lastTypingSentRef.current = null;
      }
    }, 2000);
  }, [publishJson]);

  const sendPresenceViewing = useCallback((documentId) => {
    if (documentId === activeDocumentRef.current) {
      publishJson(`/app/documents/${documentId}/presence/viewing`);
    }
  }, [publishJson]);

  return (
    <WebSocketContext.Provider
      value={{
        connected,
        lastMessage,
        presenceMessage,
        accessMessage,
        unsubscribeFromDocument,
        sendPresenceTyping,
        sendPresenceViewing,
        sendUpdate,
        subscribeToDocument,
        subscribeToWhiteboard,
      }}
    >
      {children}
    </WebSocketContext.Provider>
  );
};

export const useWebSocket = () => useContext(WebSocketContext);
