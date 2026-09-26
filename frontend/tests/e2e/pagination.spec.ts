import { test, expect } from "@playwright/test";

const api = process.env.E2E_API_URL || process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

test("pagination, authenticated landing creation, and creation from the second page stay consistent", async ({ page, request, baseURL }) => {
  const run = Date.now().toString(36);
  await page.goto("/register");
  await page.getByLabel("Email address").fill(`shortify-e2e-${run}-pages@example.test`);
  await page.getByLabel("Password", { exact: true }).fill("Shortify-test-2026!");
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  const accessToken = await page.evaluate(() => JSON.parse(sessionStorage.getItem("shortify.session")!).accessToken as string);
  for (let i = 0; i < 11; i++) {
    const result = await request.post(`${api}/api/urls`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { originalUrl: `${baseURL}/?page=${i}`, customAlias: `page-${run}-${i}` },
    });
    expect(result.status()).toBe(201);
  }
  await page.getByRole("button", { name: "Refresh links" }).click();
  await expect(page.getByRole("navigation", { name: "Link pagination" })).toContainText("Page 1 of 2");
  await expect(page.getByRole("table").locator("tbody tr")).toHaveCount(10);
  await page.getByRole("button", { name: "Next →", exact: true }).click();
  await expect(page.getByRole("navigation", { name: "Link pagination" })).toContainText("Page 2 of 2");
  await expect(page.getByRole("table").locator("tbody tr")).toHaveCount(1);
  await expect(page.getByRole("button", { name: "Next →", exact: true })).toBeDisabled();
  await page.getByLabel(/Destination URL/).fill(`${baseURL}/?newest=1`);
  await page.getByLabel(/Custom alias/).fill(`newest-${run}`);
  await page.getByRole("button", { name: "Create short link" }).click();
  await expect(page.getByRole("heading", { name: "Your short link is ready." })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Link pagination" })).toContainText("Page 1 of 2");
  await expect(page.getByRole("table").locator("tbody tr").first()).toContainText(`newest-${run}`);
  await page.getByLabel("Per page").selectOption("20");
  await expect(page.getByRole("table").locator("tbody tr")).toHaveCount(12);
  await expect(page.getByRole("navigation", { name: "Link pagination" })).toContainText("Page 1 of 1");
  await page.getByRole("link", { name: "Create a link", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Less link. More possibility.", exact: true })).toBeVisible();
  await page.getByLabel(/Destination URL/).fill(`${baseURL}/?landing=1`);
  await page.getByLabel(/Custom alias/).fill(`landing-${run}`);
  await page.getByRole("button", { name: "Create short link" }).click();
  await expect(page.getByRole("heading", { name: "Your short link is ready." })).toBeVisible();
  await page.getByRole("link", { name: "Dashboard", exact: true }).click();
  await expect(page.getByRole("table").locator("tbody tr").first()).toContainText(`landing-${run}`);
});
