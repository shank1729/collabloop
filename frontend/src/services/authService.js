const apiBaseUrl = (process.env.REACT_APP_API_BASE_URL || "http://localhost:8080").replace(/\/+$/, "");
export const AUTH_REQUEST_TIMEOUT_MS = 60000;
const sessionStorageKey = "collab_auth_session";
const sessionExpiredEventName = "collab-session-expired";

// Decoding is only a UI hint. Signature and expiry enforcement belong to the server.
export function getTokenExpiryMs(token) {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const encoded = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const { exp } = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, "=")));
    return Number.isSafeInteger(exp) && exp > 0 && Number.isSafeInteger(exp * 1000) ? exp * 1000 : null;
  } catch {
    return null;
  }
}

export function notifySessionExpired(expectedToken) {
  if (!expectedToken || getStoredSession()?.token === expectedToken) clearSession();
  window.dispatchEvent(new CustomEvent(sessionExpiredEventName, { detail: { token: expectedToken } }));
}

async function parseJsonResponse(response, { expireSessionOnUnauthorized = false } = {}) {
  if (!response.ok) {
    if (response.status === 401 && expireSessionOnUnauthorized) {
      notifySessionExpired();
    }

    let errorMessage = "Unable to complete authentication. Please try again.";

    try {
      const errorBody = await response.json();
      const validationMessages = Object.values(errorBody.validationErrors || {});
      errorMessage = validationMessages.length
        ? validationMessages.join(" ")
        : errorBody.message || errorMessage;
    } catch {
      // A proxy may return HTML instead of the API's JSON error response.
    }

    if (response.status === 401) {
      errorMessage = "Invalid email or password.";
    } else if (response.status === 429) {
      const retryAfter = response.headers?.get("Retry-After");
      const seconds = retryAfter && /^\d+$/.test(retryAfter) ? Number(retryAfter) : NaN;
      errorMessage = Number.isSafeInteger(seconds) && seconds > 0
        ? `Too many attempts. Try again in ${seconds} seconds.`
        : "Too many attempts. Please wait a moment before trying again.";
    } else if (response.status >= 500) {
      errorMessage = "The server is temporarily unavailable. Please try again shortly.";
    }

    throw new Error(errorMessage);
  }

  return response.json();
}

async function postJson(path, payload) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), AUTH_REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(`${apiBaseUrl}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    // Await the body too, so the timeout covers the entire response.
    return await parseJsonResponse(response);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(
        "The server did not respond within 60 seconds. It may be waking up or unavailable. " +
        (path === "/auth/register"
          ? "Your account may have been created. Try signing in before registering again."
          : "Please try signing in again shortly.")
      );
    }
    if (error instanceof TypeError) {
      throw new Error("Unable to reach the server. Check your connection and try again. If this continues, the service may be unavailable.");
    }
    if (error instanceof SyntaxError) {
      throw new Error("The server returned an unexpected response. Please try again shortly.");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export function getStoredSession() {
  const rawSession = localStorage.getItem(sessionStorageKey);

  if (!rawSession) {
    return null;
  }

  try {
    const session = JSON.parse(rawSession);
    return session?.token ? session : null;
  } catch {
    clearSession();
    return null;
  }
}

export function saveSession(session) {
  localStorage.setItem(sessionStorageKey, JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem(sessionStorageKey);
}

export function getSessionExpiredEventName() {
  return sessionExpiredEventName;
}

export function getAuthHeaders() {
  const session = getStoredSession();

  if (!session?.token) {
    return {};
  }

  return {
    Authorization: `Bearer ${session.token}`,
  };
}

export function login(payload) {
  return postJson("/auth/login", payload);
}

export function register(payload) {
  return postJson("/auth/register", payload);
}
