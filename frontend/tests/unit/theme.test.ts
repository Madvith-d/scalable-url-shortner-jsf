import { test } from "node:test";
import assert from "node:assert/strict";
import { runInNewContext } from "node:vm";
import { parseTheme, resolveTheme, themeBootstrapScript } from "../../lib/theme.ts";

for (const preference of ["light", "dark", "system"] as const) {
  test(`parses the ${preference} appearance preference`, () => {
    assert.equal(parseTheme(preference), preference);
  });
}

for (const value of [null, "", "invalid", "DARK", "<script>"]) {
  test(`invalid stored preference ${JSON.stringify(value)} falls back to System`, () => {
    assert.equal(parseTheme(value), "system");
  });
}

test("System follows the operating system, while explicit preferences take precedence", () => {
  assert.equal(resolveTheme("system", true), "dark");
  assert.equal(resolveTheme("system", false), "light");
  assert.equal(resolveTheme("light", true), "light");
  assert.equal(resolveTheme("dark", false), "dark");
});

function bootstrap(stored: string | null, systemDark: boolean, blocked = false) {
  const document = { documentElement: { dataset: {} as Record<string, string> } };
  runInNewContext(themeBootstrapScript, {
    document,
    window: {
      localStorage: { getItem() { if (blocked) throw new Error("Storage blocked"); return stored; } },
      matchMedia: () => ({ matches: systemDark }),
    },
  });
  return document.documentElement.dataset.theme;
}

test("pre-paint script applies a saved preference instead of the system preference", () => {
  assert.equal(bootstrap("dark", false), "dark");
  assert.equal(bootstrap("light", true), "light");
});

test("pre-paint script uses system appearance on the first visit and for invalid storage", () => {
  assert.equal(bootstrap(null, true), "dark");
  assert.equal(bootstrap("system", false), "light");
  assert.equal(bootstrap("unexpected", true), "dark");
});

test("blocked storage still produces the correct system appearance before paint", () => {
  assert.equal(bootstrap(null, true, true), "dark");
  assert.equal(bootstrap(null, false, true), "light");
});
