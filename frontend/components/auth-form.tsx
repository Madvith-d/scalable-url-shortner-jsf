"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useAuth } from "./auth-provider";
import { errorMessage, fetchApi, isAbort } from "@/lib/api";
import { safeNext, validateCredentials } from "@/lib/helpers";
import type { AuthResponse } from "@/lib/types";

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const { signIn, session } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const hasDraft = params.get("draft") === "1";
  const register = mode === "register";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => { if (session) router.replace(next); }, [session, router, next]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current) return;
    const validation = validateCredentials(email, password);
    if (validation) { setError(validation); return; }
    setError("");
    setBusy(true);
    const controller = new AbortController();
    pending.current = controller;
    try {
      const response = await fetchApi<AuthResponse>(`/api/auth/${mode}`, { method: "POST", body: JSON.stringify({ email: email.trim(), password }), signal: controller.signal });
      if (!controller.signal.aborted) { signIn(response); router.replace(next); }
    } catch (failure) { if (!isAbort(failure)) setError(errorMessage(failure)); }
    finally { pending.current = null; setBusy(false); }
  }

  if (session) return <div className="container"><p role="status">Opening your workspace…</p></div>;
  const alternate = `/${register ? "login" : "register"}?next=${encodeURIComponent(next)}${hasDraft ? "&draft=1" : ""}`;
  return (
    <div className="container auth-layout">
      <section className="panel auth-panel" aria-labelledby="auth-title">
        <h1 id="auth-title">{register ? "Create your account" : "Sign in to Shortify"}</h1>
        <p className="muted">{register ? "Save your links and view their activity." : "Access your saved links and activity."}</p>
        {hasDraft && (
          <p className="info-note">Your link draft is saved in this tab. After signing in, review it on your dashboard and create your link.</p>
        )}
        <form onSubmit={submit} aria-busy={busy}>
          <div className="field">
            <label htmlFor="email">Email address</label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              maxLength={254}
              value={email}
              onChange={event => setEmail(event.target.value)}
              required
              disabled={busy}
            />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <div className="password-field">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete={register ? "new-password" : "current-password"}
                value={password}
                onChange={event => setPassword(event.target.value)}
                required
                minLength={8}
                aria-describedby="password-help"
                disabled={busy}
              />
              <button type="button" onClick={() => setShowPassword(value => !value)} aria-controls="password" aria-pressed={showPassword}>
                {showPassword ? "Hide" : "Show"}<span className="sr-only"> password</span>
              </button>
            </div>
            <p className="field-hint" id="password-help">8–72 characters; maximum 72 UTF-8 bytes. Spaces are not trimmed.</p>
          </div>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="button full-width" type="submit" disabled={busy}>
            {busy ? (register ? "Creating account…" : "Signing in…") : (register ? "Create account" : "Sign in")}
          </button>
        </form>
        <p className="auth-switch">
          {register ? "Already have an account?" : "Need an account?"}{" "}
          <Link href={alternate}>{register ? "Sign in" : "Create an account"}</Link>
        </p>
        <p className="security-note">Your session stays in this browser tab. Sign out when using a shared device.</p>
      </section>
    </div>
  );
}
