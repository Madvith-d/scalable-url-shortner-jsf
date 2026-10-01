import { test, expect, type Page } from "@playwright/test";
import jsQR from "jsqr";

const link = {
  id: 5757, shortCode: "qr-fixture", shortUrl: "https://short.example.test/qr-fixture",
  originalUrl: "https://example.test/destination", createdAt: "2026-01-01T00:00:00Z",
  expiresAt: null, activatesAt: null as string | null, maxClicks: null as number | null, clickCount: 0, active: true,
};

async function fixture(page: Page) {
  await page.addInitScript(() => {
    sessionStorage.setItem("shortify.session", JSON.stringify({
      accessToken: "link-fixture-session", email: "fixture@example.test", expiresAt: Date.now() + 3_600_000,
    }));
  });
  let current = { ...link };
  let payload: Record<string, unknown> | null = null;
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/urls" && request.method() === "POST") {
      payload = request.postDataJSON();
      current = { ...link, ...payload };
      await route.fulfill({ status: 201, json: current });
    } else if (path === "/api/urls") {
      await route.fulfill({ json: { content: [current], page: 0, size: 10, totalElements: 1, totalPages: 1 } });
    } else if (path === `/api/urls/${link.id}`) {
      await route.fulfill({ json: current });
    } else await route.abort();
  });
  return { payload: () => payload };
}

async function assertQr(page: Page) {
  const dialog = page.getByRole("dialog", { name: "Share your short link" });
  await expect(dialog).toBeVisible();
  const image = dialog.getByRole("img", { name: `QR code for ${link.shortUrl}` });
  await expect(image).toBeVisible();
  const pixels = await image.evaluate(async element => {
    const img = element as HTMLImageElement;
    await img.decode();
    const canvas = document.createElement("canvas");
    canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
    const context = canvas.getContext("2d")!;
    context.drawImage(img, 0, 0);
    return { width: canvas.width, height: canvas.height, data: Array.from(context.getImageData(0, 0, canvas.width, canvas.height).data) };
  });
  expect(jsQR(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height)?.data).toBe(link.shortUrl);
  return dialog;
}

test("schedule/cap creation persists UTC payload and offers a decodable QR without consuming clicks", async ({ page }) => {
  const state = await fixture(page);
  let publicRedirects = 0;
  page.on("request", request => { if (request.url() === link.shortUrl) publicRedirects++; });
  await page.goto("/dashboard");
  await page.getByLabel(/Destination URL/).fill(link.originalUrl);
  await page.getByLabel(/Scheduled activation/).fill("2099-01-01T12:00");
  await page.getByLabel(/Click cap/).fill("5");
  await page.getByRole("button", { name: "Create short link", exact: true }).click();
  const result = page.locator(".created-result");
  await expect(result).toContainText("Scheduled");
  expect(state.payload()).toEqual({ originalUrl: link.originalUrl, activatesAt: "2099-01-01T12:00:00.000Z", maxClicks: 5 });
  await result.getByRole("button", { name: `QR & share ${link.shortCode}` }).click();
  const dialog = await assertQr(page);
  const downloadEvent = page.waitForEvent("download");
  await dialog.getByRole("link", { name: "Download PNG" }).click();
  expect((await downloadEvent).suggestedFilename()).toBe("shortify-qr-fixture.png");
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await expect(result.getByRole("button", { name: `QR & share ${link.shortCode}` })).toBeFocused();
  expect(publicRedirects).toBe(0);
});

test("existing dashboard links and details provide accessible mobile QR sharing", async ({ page }) => {
  await fixture(page);
  await page.goto("/dashboard");
  await page.getByRole("row").filter({ hasText: link.shortCode }).getByRole("button", { name: `QR & share ${link.shortCode}` }).click();
  await assertQr(page);
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.goto(`/urls/${link.id}`);
  await page.setViewportSize({ width: 320, height: 740 });
  await page.getByRole("button", { name: `QR & share ${link.shortCode}` }).click();
  const dialog = await assertQr(page);
  expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await dialog.getByRole("button", { name: "Close", exact: true }).click();
  await expect(page.getByRole("button", { name: `QR & share ${link.shortCode}` })).toBeFocused();
});

test("native sharing receives exact short URL and PNG, and cancellation is not an error", async ({ page }) => {
  await fixture(page);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "canShare", { configurable: true, value: () => true });
    Object.defineProperty(navigator, "share", { configurable: true, value: async (data: ShareData) => {
      (window as unknown as { shared: unknown }).shared = { url: data.url, files: data.files?.map(file => ({ name: file.name, type: file.type, size: file.size })) };
    } });
  });
  await page.goto(`/urls/${link.id}`);
  await page.getByRole("button", { name: `QR & share ${link.shortCode}` }).click();
  const dialog = await assertQr(page);
  await dialog.getByRole("button", { name: "Share link", exact: true }).click();
  await expect(dialog).toContainText("Link shared.");
  expect(await page.evaluate(() => (window as unknown as { shared: { url: string } }).shared.url)).toBe(link.shortUrl);
  await dialog.getByRole("button", { name: "Share QR image" }).click();
  await expect(dialog).toContainText("QR code shared.");
  const files = await page.evaluate(() => (window as unknown as { shared: { files: { name: string; type: string; size: number }[] } }).shared.files);
  expect(files[0]).toMatchObject({ name: "shortify-qr-fixture.png", type: "image/png" });
  expect(files[0].size).toBeGreaterThan(100);
  await page.evaluate(() => Object.defineProperty(navigator, "share", { value: async () => { throw new DOMException("Cancelled", "AbortError"); } }));
  await dialog.getByRole("button", { name: "Share link", exact: true }).click();
  await expect(dialog).not.toContainText("Sharing was unavailable");
});

test("unsupported sharing falls back to clipboard and PNG download with manual-copy recovery", async ({ page, context }) => {
  await fixture(page);
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "share", { configurable: true, value: undefined });
    Object.defineProperty(navigator, "canShare", { configurable: true, value: undefined });
  });
  await page.goto(`/urls/${link.id}`);
  await page.getByRole("button", { name: `QR & share ${link.shortCode}` }).click();
  const dialog = await assertQr(page);
  await dialog.getByRole("button", { name: "Share link", exact: true }).click();
  await expect(dialog).toContainText("Link copied to clipboard");
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link.shortUrl);
  await dialog.getByRole("button", { name: "Share QR image" }).click();
  await expect(dialog).toContainText("Download the PNG below");
  await page.evaluate(() => Object.defineProperty(navigator.clipboard, "writeText", { value: async () => { throw new Error("Blocked"); } }));
  await dialog.getByRole("button", { name: "Share link", exact: true }).click();
  await expect(dialog).toContainText("Copy the URL below");
  await expect(dialog.getByLabel("Short URL", { exact: true })).toHaveValue(link.shortUrl);
});
