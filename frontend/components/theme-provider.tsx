"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { parseTheme, resolveTheme, THEME_STORAGE_KEY, type ThemePreference } from "@/lib/theme";

type ThemeContextValue = {
  preference: ThemePreference;
  ready: boolean;
  setPreference: (preference: ThemePreference) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>("system");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let stored: ThemePreference = "system";
    try { stored = parseTheme(window.localStorage.getItem(THEME_STORAGE_KEY)); } catch {}
    setPreferenceState(stored);
    setReady(true);

    const syncPreference = (event: StorageEvent) => {
      if (event.key === THEME_STORAGE_KEY || event.key === null) {
        setPreferenceState(parseTheme(event.newValue));
      }
    };
    window.addEventListener("storage", syncPreference);
    return () => window.removeEventListener("storage", syncPreference);
  }, []);

  useEffect(() => {
    if (!ready) return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      document.documentElement.dataset.theme = resolveTheme(preference, media.matches);
    };
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [preference, ready]);

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next);
    document.documentElement.dataset.theme = resolveTheme(next, window.matchMedia("(prefers-color-scheme: dark)").matches);
    try { window.localStorage.setItem(THEME_STORAGE_KEY, next); } catch {}
  }, []);

  return <ThemeContext.Provider value={{ preference, ready, setPreference }}>{children}</ThemeContext.Provider>;
}

export function ThemeControl() {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error("ThemeControl requires ThemeProvider.");

  return (
    <label className="theme-control">
      <svg className="theme-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="8" stroke="currentColor" strokeWidth="1.6" />
        <path d="M12 4a8 8 0 0 1 0 16V4Z" fill="currentColor" />
      </svg>
      <span className="sr-only">Appearance</span>
      <select
        className="theme-select"
        aria-label="Appearance"
        value={theme.preference}
        disabled={!theme.ready}
        onChange={event => theme.setPreference(parseTheme(event.target.value))}
      >
        <option value="system">System</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </label>
  );
}
