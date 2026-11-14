import { useLayoutEffect, useState } from "react";
import "./App.css";
import AuthScreen from "./components/AuthScreen";
import DocumentEditor from "./components/DocumentEditor";
import LandingPage from "./components/LandingPage";
import { WebSocketProvider } from "./context/WebSocketContext";
import { useAppNavigation } from "./navigation";
import {
  clearSession,
  getSessionExpiredEventName,
  getStoredSession,
  saveSession,
} from "./services/authService";

function App() {
  const [session, setSession] = useState(() => getStoredSession());
  const [authMessage, setAuthMessage] = useState("");
  const { screen, documentId, whiteboard, navigate } = useAppNavigation();
  const view = screen === "workspace" && !session ? "login" : screen;

  const handleAuthSuccess = (nextSession) => {
    saveSession(nextSession);
    setSession(nextSession);
    setAuthMessage("");
    if (screen !== "workspace") navigate("workspace", { replace: true });
  };

  const handleLogout = () => {
    clearSession();
    setSession(null);
    setAuthMessage("");
    navigate("landing", { replace: true });
  };

  // Subscribe before child passive effects can report an already-expired token.
  useLayoutEffect(() => {
    const handleSessionExpired = (event) => {
      const expiredToken = event.detail?.token;
      if (expiredToken && expiredToken !== session?.token) return;
      // Another tab may have signed in more recently using the same storage key.
      if (!expiredToken || getStoredSession()?.token === expiredToken) clearSession();
      setSession(null);
      setAuthMessage("Your session expired. Please sign in again.");
      navigate("login", { replace: true });
    };

    const eventName = getSessionExpiredEventName();
    window.addEventListener(eventName, handleSessionExpired);

    return () => {
      window.removeEventListener(eventName, handleSessionExpired);
    };
  }, [session?.token, navigate]);

  useLayoutEffect(() => {
    document.documentElement.scrollTop = 0;
  }, [view, documentId]);

  if (view === "landing") {
    return <LandingPage signedIn={Boolean(session)}
      onEnter={() => navigate(session ? "workspace" : "register")}
      onSignIn={() => navigate("login")} />;
  }

  return (
    <div className="app-shell">
      {view === "workspace" ? (
        <WebSocketProvider token={session.token}>
          <DocumentEditor session={session} onLogout={handleLogout} navigate={navigate}
            selectedDocumentId={documentId} showWhiteboard={whiteboard} />
        </WebSocketProvider>
      ) : (
        <AuthScreen key={view} onAuthSuccess={handleAuthSuccess} initialMessage={authMessage} mode={view}
          onModeChange={navigate} onBack={() => { setAuthMessage(""); navigate("landing"); }} />
      )}
    </div>
  );
}

export default App;
