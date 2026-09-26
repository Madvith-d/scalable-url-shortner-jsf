export const THEME_STORAGE_KEY = "shortify.theme";
export type ThemePreference = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";

export function parseTheme(value: string | null): ThemePreference {
  return value === "light" || value === "dark" ? value : "system";
}

export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  return preference === "system" ? (systemDark ? "dark" : "light") : preference;
}

// Runs before paint; the value is a fixed storage key, never user-supplied script.
export const themeBootstrapScript = `(() => {
  let preference = 'system';
  try {
    const stored = window.localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
    if (stored === 'light' || stored === 'dark') preference = stored;
  } catch {}
  const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = preference === 'system'
    ? (systemDark ? 'dark' : 'light') : preference;
})();`;
