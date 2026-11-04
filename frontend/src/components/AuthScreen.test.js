import { act, fireEvent, render, screen } from "@testing-library/react";
import AuthScreen from "./AuthScreen";
import { login, register } from "../services/authService";

jest.mock("../services/authService", () => ({ login: jest.fn(), register: jest.fn() }));

function fillCredentials() {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "person@example.com" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret123" } });
}

beforeEach(() => jest.clearAllMocks());

test("shows the error, preserves inputs, and allows a successful retry", async () => {
  login.mockRejectedValueOnce(new Error("Invalid email or password."));
  const session = { token: "test-token" };
  login.mockResolvedValueOnce(session);
  const onAuthSuccess = jest.fn();
  render(<AuthScreen onAuthSuccess={onAuthSuccess} />);
  fillCredentials();
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
  expect(screen.getByLabelText("Email")).toHaveValue("person@example.com");
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByRole("button", { name: "Sign in" });
  expect(onAuthSuccess).toHaveBeenCalledWith(session);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("shows slow-response feedback, then unlocks the form after timeout", async () => {
  jest.useFakeTimers();
  try {
    let rejectRequest;
    login.mockImplementation(() => new Promise((resolve, reject) => { rejectRequest = reject; }));
    render(<AuthScreen onAuthSuccess={jest.fn()} />);
    fillCredentials();
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(screen.getByRole("status")).toHaveTextContent("Signing you in");
    expect(screen.getByRole("button", { name: /register/i })).toBeDisabled();
    act(() => jest.advanceTimersByTime(10000));
    expect(screen.getByRole("status")).toHaveTextContent("server may be waking up");
    await act(async () => rejectRequest(new Error("The server did not respond within 60 seconds.")));
    expect(screen.getByRole("alert")).toHaveTextContent("60 seconds");
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  } finally {
    jest.useRealTimers();
  }
});

test("submits registration and lets a user switch to sign-in after a duplicate email error", async () => {
  register.mockRejectedValue(new Error("An account already exists for this email."));
  render(<AuthScreen onAuthSuccess={jest.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: /register/i }));
  fillCredentials();
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Person" } });
  fireEvent.click(screen.getByRole("button", { name: "Create account" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("account already exists");
  expect(register).toHaveBeenCalledWith({ name: "Person", email: "person@example.com", password: "secret123" });
  fireEvent.click(screen.getByRole("button", { name: /already have an account/i }));
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
});
