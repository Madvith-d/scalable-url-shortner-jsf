import { test, expect } from "@playwright/test";

test("appearance follows System, persists explicit choices, and synchronizes browser tabs", async ({ page, context }, testInfo) => {
  await page.goto("/");
  const appearance = page.getByLabel("Appearance", { exact: true });
  await expect(appearance).toBeEnabled();
  await expect(appearance).toHaveValue("system");
  await expect(page.locator("html")).toHaveAttribute("data-theme", testInfo.project.name);

  await appearance.selectOption("dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  expect(await page.evaluate(() => localStorage.getItem("shortify.theme"))).toBe("dark");
  await page.getByRole("link", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Sign in to Shortify", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(appearance).toHaveValue("dark");
  await page.emulateMedia({ colorScheme: "light" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await appearance.selectOption("light");
  const other = await context.newPage();
  await other.goto("/register");
  await expect(other.getByLabel("Appearance", { exact: true })).toHaveValue("light");
  await expect(other.locator("html")).toHaveAttribute("data-theme", "light");
  await other.getByLabel("Appearance", { exact: true }).selectOption("dark");
  await expect(appearance).toHaveValue("dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await appearance.selectOption("system");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(other.getByLabel("Appearance", { exact: true })).toHaveValue("system");
  await other.close();
});

test("appearance still works when preference storage is blocked", async ({ page }) => {
  await page.addInitScript(() => {
    const get = Storage.prototype.getItem;
    const set = Storage.prototype.setItem;
    Storage.prototype.getItem = function (key: string) {
      if (key === "shortify.theme") throw new DOMException("Storage blocked", "SecurityError");
      return get.call(this, key);
    };
    Storage.prototype.setItem = function (key: string, value: string) {
      if (key === "shortify.theme") throw new DOMException("Storage blocked", "SecurityError");
      return set.call(this, key, value);
    };
  });
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByLabel("Appearance", { exact: true }).selectOption("light");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.getByRole("link", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Sign in to Shortify", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
});

test("semantic theme colors keep text readable", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByLabel("Appearance", { exact: true })).toBeEnabled();
  const colors = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement);
    return Object.fromEntries(["canvas", "surface", "surface-subtle", "ink", "muted", "accent", "on-accent", "placeholder", "success", "success-bg", "warning", "warning-bg", "danger", "danger-bg", "info", "info-bg"].map(name => [name, style.getPropertyValue(`--${name}`).trim()]));
  });
  const luminance = (hex: string) => {
    const normalized = /^#[0-9a-f]{3}$/i.test(hex)
      ? `#${hex.slice(1).split("").map(digit => digit.repeat(2)).join("")}`
      : hex;
    expect(normalized).toMatch(/^#[0-9a-f]{6}$/i);
    const rgb = [1, 3, 5].map(offset => parseInt(normalized.slice(offset, offset + 2), 16) / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
  };
  for (const [foreground, background] of [["ink", "canvas"], ["muted", "canvas"], ["ink", "surface"], ["muted", "surface"], ["muted", "surface-subtle"], ["on-accent", "accent"], ["placeholder", "surface"], ["success", "success-bg"], ["warning", "warning-bg"], ["danger", "danger-bg"], ["info", "info-bg"]]) {
    const a = luminance(colors[foreground]);
    const b = luminance(colors[background]);
    expect((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05), `${foreground} on ${background}`).toBeGreaterThanOrEqual(4.5);
  }
});

test("saved appearance is applied before React hydrates", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("shortify.theme", "dark"));
  await page.route("**/_next/static/chunks/**", route => route.request().resourceType() === "script" ? route.abort() : route.continue());
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.getByLabel("Appearance", { exact: true })).toBeDisabled();
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe("dark");
});
