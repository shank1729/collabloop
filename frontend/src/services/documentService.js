import { getAuthHeaders, notifySessionExpired } from "./authService";

const apiBaseUrl = process.env.REACT_APP_API_BASE_URL || "http://localhost:8080";

async function parseJsonResponse(response) {
  if (!response.ok) {
    if (response.status === 401) {
      notifySessionExpired();
    }

    let errorMessage = "Request failed";

    try {
      const errorBody = await response.json();
      errorMessage = errorBody.message || errorMessage;
    } catch {
      errorMessage = `Request failed with status ${response.status}`;
    }

    const error = new Error(errorMessage);
    error.status = response.status;
    throw error;
  }

  return response.status === 204 ? undefined : response.json();
}

async function fetchWithAuth(path, options = {}) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...getAuthHeaders(),
      ...(options.headers || {}),
    },
  });

  return parseJsonResponse(response);
}

export function getDocuments() {
  return fetchWithAuth("/documents", { method: "GET" });
}

export function getDocumentById(documentId) {
  return fetchWithAuth(`/documents/${documentId}`, { method: "GET" });
}

export function createDocument(payload) {
  return fetchWithAuth("/documents", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function patchDocument(documentId, payload) {
  return fetchWithAuth(`/documents/${documentId}`, {
    method: "PATCH",
    body: JSON.stringify(payload),
  });
}

export async function saveDocumentContent(documentId, payload) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    return await fetchWithAuth(`/documents/${documentId}/content`, {
      method: "PUT", body: JSON.stringify(payload), signal: controller.signal,
    });
  } finally {
    clearTimeout(timeout);
  }
}

export function shareDocument(documentId, email, role) {
  return fetchWithAuth(`/documents/${documentId}/share`, {
    method: "POST",
    body: JSON.stringify({ email, role }),
  });
}

export function removeCollaborator(documentId, userId) {
  return fetchWithAuth(`/documents/${documentId}/collaborators/${userId}`, { method: "DELETE" });
}

export function getDocumentHistory(documentId, { signal } = {}) {
  return fetchWithAuth(`/documents/${documentId}/history`, { method: "GET", signal });
}

async function whiteboardRequest(documentId, suffix = "", options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    return await fetchWithAuth(`/documents/${documentId}/whiteboard${suffix}`, {
      ...options,
      signal: controller.signal,
    });
  } catch (error) {
    if (controller.signal.aborted) throw new Error("The whiteboard request timed out. Try again.");
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export function getWhiteboard(documentId) {
  return whiteboardRequest(documentId);
}

export function saveWhiteboardStroke(documentId, strokeId, drawing) {
  return whiteboardRequest(documentId, `/strokes/${strokeId}`, {
    method: "PUT",
    body: JSON.stringify(drawing),
  });
}

export function undoWhiteboardStroke(documentId, strokeId) {
  return whiteboardRequest(documentId, `/strokes/${strokeId}`, { method: "DELETE" });
}

export function eraseWhiteboardStrokes(documentId, strokeIds) {
  return whiteboardRequest(documentId, "/erase", {
    method: "POST",
    body: JSON.stringify({ strokeIds }),
  });
}

export async function deleteDocument(documentId) {
  const response = await fetch(`${apiBaseUrl}/documents/${documentId}`, {
    method: "DELETE",
    headers: {
      ...getAuthHeaders(),
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      notifySessionExpired();
    }

    let errorMessage = "Unable to delete the document.";

    try {
      const errorBody = await response.json();
      errorMessage = errorBody.message || errorMessage;
    } catch {
      errorMessage = `Request failed with status ${response.status}`;
    }

    throw new Error(errorMessage);
  }
}
