import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { login, register } from "../services/authService";
import BrandLockup from "./BrandLockup";

export default function AuthScreen({ onAuthSuccess, initialMessage = "", mode, onModeChange, onBack }) {
  const [localMode, setLocalMode] = useState("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [slowResponse, setSlowResponse] = useState(false);
  const [error, setError] = useState(initialMessage);
  const active = useRef(false);

  const isLogin = (mode ?? localMode) === "login";

  useLayoutEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);

  useEffect(() => {
    if (!loading) return;
    const timer = setTimeout(() => setSlowResponse(true), 10000);
    return () => clearTimeout(timer);
  }, [loading]);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (loading) return;
    setSlowResponse(false);
    setLoading(true);
    setError("");

    try {
      const payload = isLogin ? { email, password } : { name, email, password };
      const session = isLogin ? await login(payload) : await register(payload);
      if (active.current) onAuthSuccess(session);
    } catch (submitError) {
      setError(submitError.message || "Authentication failed.");
    } finally {
      setLoading(false);
      setSlowResponse(false);
    }
  };

  return (
    <main className="auth-layout">
      <section className="auth-card">
        <BrandLockup />
        <h1>{isLogin ? "Sign in to your workspace" : "Create your account"}</h1>
        {error ? <p className="auth-error" role="alert">{error}</p> : null}
        {loading ? (
          <p className="auth-copy" role="status">
            {slowResponse
              ? "Taking longer than usual. The server may be waking up. We'll wait up to 60 seconds."
              : isLogin ? "Signing you in..." : "Creating your account..."}
          </p>
        ) : null}

        <form className="auth-form" onSubmit={handleSubmit} aria-busy={loading}>
          {!isLogin ? (
            <label className="auth-field">
              <span>Name</span>
              <input
                type="text"
                autoComplete="name"
                disabled={loading}
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Enter your name"
                required
              />
            </label>
          ) : null}

          <label className="auth-field">
            <span>Email</span>
            <input
              type="email"
              autoComplete="username"
              disabled={loading}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@example.com"
              required
            />
          </label>

          <label className="auth-field">
            <span>Password</span>
            <input
              type="password"
              autoComplete={isLogin ? "current-password" : "new-password"}
              minLength={isLogin ? undefined : 6}
              disabled={loading}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Minimum 6 characters"
              required
            />
          </label>

          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? "Please wait..." : isLogin ? "Sign in" : "Create account"}
          </button>
        </form>

        <button
          className="auth-toggle"
          type="button"
          disabled={loading}
          onClick={() => {
            setError("");
            const nextMode = isLogin ? "register" : "login";
            if (onModeChange) onModeChange(nextMode);
            else setLocalMode(nextMode);
          }}
        >
          {isLogin
            ? "Need an account? Register"
            : "Already have an account? Sign in"}
        </button>
        {onBack && (
          <button className="auth-back" type="button" disabled={loading} onClick={onBack}>Back to home</button>
        )}
      </section>
    </main>
  );
}
