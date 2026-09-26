"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError, fetchApi } from "@/lib/api";
import { DRAFT_KEY, SESSION_KEY, readSession, tokenDeadline } from "@/lib/helpers";
import type { AuthResponse, Session } from "@/lib/types";

type AuthContextValue = {
  session: Session | null;
  ready: boolean;
  notice: string;
  signIn: (response: AuthResponse) => void;
  logout: (notice?: string) => void;
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
};
const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);
  const [notice, setNotice] = useState("");
  const current = useRef<Session | null>(null);
  const requests = useRef(new Set<AbortController>());

  const logout = useCallback((message = "You have been signed out.") => {
    current.current = null;
    requests.current.forEach(controller => controller.abort());
    requests.current.clear();
    try {
      sessionStorage.removeItem(SESSION_KEY);
      sessionStorage.removeItem(DRAFT_KEY);
    } catch { /* Storage can be unavailable in private browser contexts. */ }
    setSession(null);
    setNotice(message);
  }, []);

  useEffect(() => {
    let restored: Session | null = null;
    try {
      const raw = sessionStorage.getItem(SESSION_KEY);
      restored = readSession(raw);
      if (raw && !restored) {
        sessionStorage.removeItem(SESSION_KEY);
        sessionStorage.removeItem(DRAFT_KEY);
        setNotice("Your session expired. Please sign in again.");
      }
    } catch { /* Authentication still works in memory when storage is blocked. */ }
    current.current = restored;
    setSession(restored);
    setReady(true);
  }, []);

  useEffect(() => {
    if (!session) return;
    const check = () => {
      if (current.current === session && session.expiresAt <= Date.now()) logout("Your session expired. Please sign in again.");
    };
    const timer = setTimeout(check, Math.min(Math.max(0, session.expiresAt - Date.now()), 2_147_483_647));
    const interval = setInterval(check, 60_000);
    window.addEventListener("focus", check);
    document.addEventListener("visibilitychange", check);
    return () => {
      clearTimeout(timer);
      clearInterval(interval);
      window.removeEventListener("focus", check);
      document.removeEventListener("visibilitychange", check);
    };
  }, [session, logout]);

  const signIn = useCallback((response: AuthResponse) => {
    const next = { accessToken: response.accessToken, email: response.email, expiresAt: tokenDeadline(response.accessToken, response.expiresIn) };
    if (response.tokenType !== "Bearer" || !next.accessToken || !Number.isFinite(next.expiresAt) || next.expiresAt <= Date.now()) {
      throw new Error("The server returned an invalid session. Please sign in again.");
    }
    requests.current.forEach(controller => controller.abort());
    current.current = next;
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(next)); } catch { /* Keep the session in memory. */ }
    setSession(next);
    setNotice("");
  }, []);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const owner = current.current;
    if (!owner || owner.expiresAt <= Date.now()) {
      logout("Your session expired. Please sign in again.");
      throw new DOMException("Session ended", "AbortError");
    }
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (init.signal?.aborted) abort();
    init.signal?.addEventListener("abort", abort, { once: true });
    requests.current.add(controller);
    try {
      const result = await fetchApi<T>(path, { ...init, signal: controller.signal }, owner.accessToken);
      if (current.current !== owner || controller.signal.aborted) throw new DOMException("Session changed", "AbortError");
      if (owner.expiresAt <= Date.now()) {
        logout("Your session expired. Please sign in again.");
        throw new DOMException("Session ended", "AbortError");
      }
      return result;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && current.current === owner) logout("Your session ended. Please sign in again.");
      throw error;
    } finally {
      requests.current.delete(controller);
      init.signal?.removeEventListener("abort", abort);
    }
  }, [logout]);

  return <AuthContext.Provider value={{ session, ready, notice, signIn, logout, request }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("AuthProvider is required.");
  return context;
}
