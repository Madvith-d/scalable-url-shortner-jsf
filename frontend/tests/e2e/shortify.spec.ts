import { test, expect, type Page } from "@playwright/test";

const api = process.env.E2E_API_URL || process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";
const password = "Shortify-test-2026!";
const run = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const email = (name: string) => `shortify-e2e-${run}-${name}@example.test`;

async function credentials(page: Page, address: string, register = true) {
  await expect(page.getByRole("heading", { name: register ? "Create your account" : "Sign in to Shortify", exact: true })).toBeVisible();
  await page.getByLabel("Email address").fill(address);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: register ? "Create account" : "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

async function create(page: Page, alias: string, destination: string, expiration?: string) {
  await page.getByLabel(/Destination URL/).fill(destination);
  await page.getByLabel(/Custom alias/).fill(alias);
  await page.getByLabel(/Expiration.*Optional/).fill(expiration || "");
  const response = page.waitForResponse(r => r.url() === `${api}/api/urls` && r.request().method() === "POST");
  await page.getByRole("button", { name: "Create short link" }).click();
  const created = await response;
  expect(created.status()).toBe(201);
  await expect(page.getByRole("heading", { name: "Your short link is ready." })).toBeVisible();
  return created.json() as Promise<{ id: number; shortCode: string; shortUrl: string }>;
}

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
}

test("desktop: draft, accounts, owned links, redirect, analytics, state consistency and isolation", async ({ page, context, request, baseURL }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const destination = `${baseURL}/?visited=${run}`;
  const alias = `desk-${run}`;
  await page.goto("/");
  await page.screenshot({ path: testInfo.outputPath("landing-desktop.png"), fullPage: true });
  await page.getByLabel(/Destination URL/).fill(destination);
  await page.getByLabel(/Custom alias/).fill(alias);
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await expect(page).toHaveURL(/\/login\?/);
  await page.getByRole("link", { name: "Create an account" }).click();
  await credentials(page, email("owner"));
  await expect(page.getByText("Your first link starts here.")).toBeVisible();
  await expect(page.getByLabel(/Destination URL/)).toHaveValue(destination);
  await expect(page.getByLabel(/Custom alias/)).toHaveValue(alias);
  const link = await create(page, alias, destination, "2099-01-01T12:00");
  const row = page.getByRole("row").filter({ hasText: alias });
  await expect(row).toContainText("Active");
  await row.getByRole("link", { name: `Manage ${alias}`, exact: true }).click();
  await expect(page).toHaveURL(`/urls/${link.id}`);
  await expect(page.getByRole("heading", { name: alias, exact: true })).toBeVisible();
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.getByRole("button", { name: `Copy short URL ${link.shortUrl}` }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(link.shortUrl);
  await page.getByRole("link", { name: "View analytics" }).click();
  await expect(page.getByText("No clicks have been recorded yet.", { exact: false })).toBeVisible();
  const popupPromise = page.waitForEvent("popup");
  await page.getByRole("link", { name: link.shortUrl, exact: false }).click();
  const popup = await popupPromise;
  await expect(popup).toHaveURL(destination);
  await popup.close();
  await expect(async () => {
    await page.getByRole("button", { name: "Refresh analytics" }).click();
    await expect(page.locator(".primary-stat strong")).toHaveText("1");
  }).toPass({ timeout: 15_000 });
  await expect(page.getByRole("heading", { name: "Clicks over time" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Referrers", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Devices", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Geography", exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("analytics-desktop.png"), fullPage: true });
  await page.getByRole("link", { name: "Link details" }).click();
  await page.getByRole("button", { name: "Deactivate link" }).click();
  await expect(page.getByRole("button", { name: "Reactivate link" })).toBeVisible();
  expect((await request.get(link.shortUrl, { maxRedirects: 0 })).status()).toBe(410);
  await page.getByRole("link", { name: "All links" }).click();
  await expect(page.getByRole("row").filter({ hasText: alias })).toContainText("Inactive");
  await page.getByRole("row").filter({ hasText: alias }).getByRole("link", { name: `Analytics for ${alias}` }).click();
  await expect(page.locator(".primary-stat strong")).toHaveText("1");
  await expect(page.locator(".analytics-link")).toContainText("Inactive");
  await page.getByRole("link", { name: "Link details" }).click();
  await page.getByRole("button", { name: "Reactivate link" }).click();
  await expect(page.getByRole("button", { name: "Deactivate link" })).toBeVisible();
  expect((await request.get(link.shortUrl, { maxRedirects: 0 })).status()).toBe(302);
  await page.getByRole("link", { name: "Dashboard", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: alias })).toContainText("Active");
  await page.getByLabel(/Destination URL/).fill(destination);
  await page.getByLabel(/Custom alias/).fill(alias);
  await page.getByRole("button", { name: "Create short link" }).click();
  await expect(page.locator(".error-state, .form-error")).toContainText("already in use");
  await page.getByLabel(/Custom alias/).fill("dashboard");
  await page.getByRole("button", { name: "Create short link" }).click();
  await expect(page.locator(".error-state, .form-error")).toContainText("reserved");
  await page.getByLabel(/Custom alias/).fill("");
  await page.getByLabel(/Destination URL/).fill("javascript:alert(1)");
  await page.getByRole("button", { name: "Create short link" }).click();
  await expect(page.locator(".error-state, .form-error")).toContainText("http:// or https://");
  await page.reload();
  await expect(page.getByRole("row").filter({ hasText: alias })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goBack();
  await expect(page.getByRole("row").filter({ hasText: alias })).toHaveCount(0);
  await page.goto(`/urls/${link.id}/analytics`);
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/register");
  await credentials(page, email("other"));
  await expect(page.getByText("Your first link starts here.")).toBeVisible();
  for (const path of [`/urls/${link.id}`, `/urls/${link.id}/analytics`]) {
    await page.goto(path);
    await expect(page.locator(".error-state, .form-error").first()).toContainText("not found");
    await expect(page.getByText(link.shortUrl, { exact: true })).toHaveCount(0);
  }
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/login");
  await credentials(page, email("owner"), false);
  await expect(page.getByRole("row").filter({ hasText: alias })).toBeVisible();
  await noOverflow(page);
  expect(errors).toEqual([]);
});

test("mobile: create and manage expired links, and check every route at responsive widths", async ({ page, request, baseURL }, testInfo) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 375, height: 812 });
  for (const route of ["/", "/login", "/register"]) {
    await page.goto(route);
    await noOverflow(page);
    await page.screenshot({ path: testInfo.outputPath(`${route.replaceAll("/", "") || "landing"}-mobile.png`), fullPage: true });
  }
  await credentials(page, email("mobile"));
  const alias = `mob-${run}`;
  const link = await create(page, alias, `${baseURL}/?mobile=1`, "2000-01-01T12:00");
  await expect(page.getByRole("row").filter({ hasText: alias })).toContainText("Expired");
  expect((await request.get(link.shortUrl, { maxRedirects: 0 })).status()).toBe(410);
  await page.getByRole("link", { name: "Manage this link" }).click();
  await expect(page.locator(".link-detail-panel")).toContainText("Expired");
  await page.getByRole("button", { name: /Deactivate link|Reactivate link/ }).click();
  await expect(page.getByRole("status").filter({ hasText: /Link deactivated|Link reactivated/ })).toBeVisible();
  expect((await request.get(link.shortUrl, { maxRedirects: 0 })).status()).toBe(410);
  for (const width of [320, 375, 414, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ["/", "/dashboard", `/urls/${link.id}`, `/urls/${link.id}/analytics`]) {
      await page.goto(route);
      await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
      if (route === "/dashboard") await expect(page.getByRole("row").filter({ hasText: alias })).toBeVisible();
      if (route.endsWith("/analytics")) await expect(page.locator(".primary-stat strong")).toHaveText("0");
      await noOverflow(page);
      if (width === 375 || width === 1440) await page.screenshot({ path: testInfo.outputPath(`${route.replaceAll("/", "-") || "landing"}-${width}.png`), fullPage: true });
    }
  }
  await page.getByRole("button", { name: "Sign out" }).click();
  for (const width of [320, 375, 414, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ["/login", "/register"]) {
      await page.goto(route);
      await noOverflow(page);
    }
  }
});

test("network errors recover, missing routes stay safe, and expired or rejected sessions clear", async ({ page }) => {
  await page.goto("/register");
  await credentials(page, email("errors"));
  await page.route(`${api}/api/urls?*`, route => route.abort("failed"));
  await page.getByRole("button", { name: "Refresh links" }).click();
  await expect(page.locator(".error-state, .form-error")).toBeVisible();
  await page.unroute(`${api}/api/urls?*`);
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByText("Your first link starts here.")).toBeVisible();
  await page.goto("/urls/999999999999");
  await expect(page.locator(".error-state, .form-error")).toContainText("not found");
  await page.goto("/urls/invalid");
  await expect(page.getByRole("heading", { name: "This page isn’t here." })).toBeVisible();
  await page.goto("/dashboard");
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem("shortify.session")!);
    session.expiresAt = Date.now() - 1000;
    sessionStorage.setItem("shortify.session", JSON.stringify(session));
  });
  await page.reload();
  await expect(page).toHaveURL(/\/login/);
  expect(await page.evaluate(() => sessionStorage.getItem("shortify.session"))).toBeNull();
  await credentials(page, email("errors"), false);
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem("shortify.session")!);
    session.accessToken = "invalid.jwt.token";
    sessionStorage.setItem("shortify.session", JSON.stringify(session));
  });
  await page.reload();
  await expect(page).toHaveURL(/\/login/);
  expect(await page.evaluate(() => sessionStorage.getItem("shortify.session"))).toBeNull();
});
