import { AUTH_REQUEST_TIMEOUT_MS, login, register, saveSession, clearSession, getStoredSession, notifySessionExpired } from "./authService";

const originalFetch = global.fetch;
const payload = { email: "person@example.com", password: "secret123" };
const response = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  json: jest.fn().mockResolvedValue(body),
});

beforeEach(() => {
  jest.useFakeTimers();
  clearSession();
  global.fetch = jest.fn();
});

test("expiry of an old token preserves a newer login stored by another tab", () => {
  saveSession({ token: "new-token" });
  notifySessionExpired("old-token");
  expect(getStoredSession()).toEqual({ token: "new-token" });
  notifySessionExpired("new-token");
  expect(getStoredSession()).toBeNull();
});

afterEach(() => {
  global.fetch = originalFetch;
  jest.useRealTimers();
});

test("returns the session on success and clears the deadline", async () => {
  const session = { token: "test-token", email: payload.email };
  global.fetch.mockResolvedValue(response(200, session));
  await expect(login(payload)).resolves.toEqual(session);
  expect(global.fetch).toHaveBeenCalledWith(expect.stringMatching(/\/auth\/login$/), expect.objectContaining({
    method: "POST",
    body: JSON.stringify(payload),
    signal: expect.any(AbortSignal),
  }));
  expect(jest.getTimerCount()).toBe(0);
});

test.each([
  [401, {}, /invalid email or password/i],
  [400, { message: "An account already exists for this email." }, /account already exists/i],
  [400, { message: "Validation failed", validationErrors: { password: "Password must contain at least 6 characters" } }, /at least 6 characters/],
  [429, {}, /too many attempts/i],
  [503, {}, /temporarily unavailable/i],
])("provides an actionable error for HTTP %s", async (status, body, expected) => {
  global.fetch.mockResolvedValue(response(status, body));
  await expect(register(payload)).rejects.toThrow(expected);
  expect(jest.getTimerCount()).toBe(0);
});

test("handles a proxy returning HTML on failure", async () => {
  global.fetch.mockResolvedValue({
    ...response(502, null),
    json: jest.fn().mockRejectedValue(new SyntaxError("Not JSON")),
  });
  await expect(login(payload)).rejects.toThrow(/temporarily unavailable/i);
});

test("shows the server retry interval without automatically retrying", async () => {
  global.fetch.mockResolvedValue({
    ...response(429, {}),
    headers: { get: jest.fn().mockReturnValue("37") },
  });
  await expect(login(payload)).rejects.toThrow("Try again in 37 seconds");
  expect(global.fetch).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});

test.each(["-1", "0", "not-a-number", "Infinity", "9007199254740992"])(
  "falls back to a safe message for an invalid retry interval: %s", async (retryAfter) => {
    global.fetch.mockResolvedValue({
      ...response(429, {}),
      headers: { get: jest.fn().mockReturnValue(retryAfter) },
    });
    await expect(register(payload)).rejects.toThrow("Please wait a moment");
  }
);

test("explains network failures", async () => {
  global.fetch.mockRejectedValue(new TypeError("Failed to fetch"));
  await expect(login(payload)).rejects.toThrow(/check your connection/i);
  expect(jest.getTimerCount()).toBe(0);
});

test.each([login, register])("aborts a pending auth request after the deadline", async (authenticate) => {
  global.fetch.mockImplementation((url, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
  }));
  const result = authenticate(payload);
  const assertion = expect(result).rejects.toThrow(
    authenticate === register ? /account may have been created/i : /60 seconds/i
  );
  jest.advanceTimersByTime(AUTH_REQUEST_TIMEOUT_MS);
  await assertion;
  expect(global.fetch.mock.calls[0][1].signal.aborted).toBe(true);
  expect(jest.getTimerCount()).toBe(0);
});

test("keeps the deadline active while reading the response body", async () => {
  global.fetch.mockImplementation(async (url, { signal }) => ({
    ok: true,
    json: () => new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
    }),
  }));
  const result = login(payload);
  const assertion = expect(result).rejects.toThrow(/60 seconds/i);
  await Promise.resolve();
  jest.advanceTimersByTime(AUTH_REQUEST_TIMEOUT_MS);
  await assertion;
});
