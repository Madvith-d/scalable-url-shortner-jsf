import { test, expect, type Page, type Route } from "@playwright/test";
import type { GeographyAnalytics, GeographyBy } from "../../lib/types";

const fixtureId = 424242;
const fixturePath = `/urls/${fixtureId}/analytics`;
const countryData: GeographyAnalytics = {
  totalClicks: 100,
  buckets: [{ label: "US", clicks: 40 }, { label: "GB", clicks: 20 }, { label: "Unknown", clicks: 10 }],
};
const longCity = "A very long city name with multiple districts and an exceptionallylongunbrokensegmentthatmustwrapwithoutclipping, GB";
const cityData: GeographyAnalytics = {
  totalClicks: 100,
  buckets: [{ label: "New York, US", clicks: 30 }, { label: longCity, clicks: 20 }, { label: "Unknown", clicks: 15 }],
};

async function installAnalyticsFixture(page: Page, geography: (route: Route, by: GeographyBy) => Promise<void>) {
  const requests = { link: 0, summary: 0, country: 0, city: 0 };
  await page.addInitScript(() => {
    sessionStorage.setItem("shortify.session", JSON.stringify({
      accessToken: "geography-fixture-session",
      email: "geography-fixture@example.test",
      expiresAt: Date.now() + 3_600_000,
    }));
  });
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname === `/api/urls/${fixtureId}/analytics/geography`) {
      const by = url.searchParams.get("by");
      expect(["country", "city"]).toContain(by);
      requests[by as GeographyBy]++;
      await geography(route, by as GeographyBy);
    } else if (url.pathname === `/api/urls/${fixtureId}/analytics`) {
      requests.summary++;
      await route.fulfill({ json: {
        totalClicks: 200,
        clicksOverTime: [{ date: "2026-09-01", clicks: 200 }],
        referrers: [{ label: "Direct", clicks: 200 }],
        devices: [{ label: "Desktop", clicks: 200 }],
        geography: [{ label: "SUMMARY MUST NOT BE USED", clicks: 200 }],
      } });
    } else if (url.pathname === `/api/urls/${fixtureId}`) {
      requests.link++;
      await route.fulfill({ json: {
        id: fixtureId,
        shortCode: "geography-fixture",
        shortUrl: "https://short.example.test/geography-fixture",
        originalUrl: "https://example.test/geography-fixture",
        createdAt: "2026-09-01T00:00:00Z",
        expiresAt: null,
        active: true,
      } });
    } else {
      await route.abort("blockedbyclient");
    }
  });
  return requests;
}

function heldRoute() {
  let resolve!: (route: Route) => void;
  const promise = new Promise<Route>(done => { resolve = done; });
  return { promise, resolve };
}

test("fixture: known country/city buckets, all-click percentages, selected refresh and long city text on desktop/mobile in each theme", async ({ page }, testInfo) => {
  const requests = await installAnalyticsFixture(page, async (route, by) => {
    await route.fulfill({ json: by === "country" ? countryData : cityData });
  });
  await page.goto(fixturePath);
  await page.getByLabel("Appearance", { exact: true }).selectOption(testInfo.project.name);
  await expect(page.locator("html")).toHaveAttribute("data-theme", testInfo.project.name);
  const geography = page.getByRole("region", { name: "Geography", exact: true });
  const selector = geography.getByRole("combobox", { name: "Geography view" });
  await expect(selector).toHaveValue("country");
  await expect(geography.locator(".bucket-label")).toHaveText(["US", "GB", "Unknown"]);
  await expect(geography.locator(".bucket-percent")).toHaveText(["40%", "20%", "10%"]);
  await expect(geography.locator(".bar-track > span").first()).toHaveAttribute("style", "width: 40%;");
  await expect(page.getByText("SUMMARY MUST NOT BE USED")).toHaveCount(0);
  await expect(page.locator(".primary-stat strong")).toHaveText("200");
  await selector.focus();
  await expect(selector).toBeFocused();
  await selector.press("ArrowDown");
  await selector.press("Enter");
  await expect(selector).toHaveValue("city");
  await expect(geography.getByRole("list", { name: "City breakdown" })).toBeVisible();
  await expect(geography.locator(".bucket-label")).toHaveText(["New York, US", longCity, "Unknown"]);
  await expect(geography.locator(".bucket-percent")).toHaveText(["30%", "20%", "15%"]);
  await expect(geography.locator(".bar-track > span").first()).toHaveAttribute("style", "width: 30%;");
  const before = { ...requests };
  await page.getByRole("button", { name: "Refresh analytics" }).click();
  await expect(page.getByRole("button", { name: "Refresh analytics" })).toBeEnabled();
  await expect(selector).toHaveValue("city");
  await expect(geography.locator(".bucket-label").first()).toHaveText("New York, US");
  expect(requests).toEqual({ link: before.link + 1, summary: before.summary + 1, city: before.city + 1, country: before.country });
  for (const width of [320, 375, 768, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    await expect(geography.getByText(longCity, { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    expect(await geography.getByRole("list").evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    if (width === 375 || width === 1440) {
      await geography.screenshot({ path: testInfo.outputPath(`fixture-city-${width}.png`) });
    }
  }
  await selector.selectOption("country");
  await expect(geography.locator(".bucket-label")).toHaveText(["US", "GB", "Unknown"]);
  await expect(geography.getByText(longCity, { exact: true })).toHaveCount(0);
});

test("fixture: country-only location keeps country known and city Unknown; empty selected geography can refresh", async ({ page }) => {
  let empty = false;
  await installAnalyticsFixture(page, async (route, by) => {
    await route.fulfill({ json: empty ? { totalClicks: 0, buckets: [] } : {
      totalClicks: 8,
      buckets: [{ label: by === "country" ? "DE" : "Unknown", clicks: 8 }],
    } });
  });
  await page.goto(fixturePath);
  const geography = page.getByRole("region", { name: "Geography", exact: true });
  const selector = geography.getByRole("combobox", { name: "Geography view" });
  await expect(geography.locator(".bucket-label")).toHaveText("DE");
  await expect(geography.locator(".bucket-percent")).toHaveText("100%");
  await selector.selectOption("city");
  await expect(geography.locator(".bucket-label")).toHaveText("Unknown");
  await expect(geography.locator(".bucket-percent")).toHaveText("100%");
  await expect(geography).toContainText("Unknown means the city could not be determined");
  empty = true;
  await page.getByRole("button", { name: "Refresh analytics" }).click();
  await expect(selector).toHaveValue("city");
  await expect(geography.getByText("No city data yet.", { exact: true })).toBeVisible();
  await expect(geography.getByRole("listitem")).toHaveCount(0);
  await selector.selectOption("country");
  await expect(geography.getByText("No country data yet.", { exact: true })).toBeVisible();
  await expect(geography).not.toContainText("NaN");
});

test("fixture: switching geography cancels a slow prior city response even after returning to city", async ({ page }) => {
  const held = heldRoute();
  let firstCity = true;
  await installAnalyticsFixture(page, async (route, by) => {
    if (by === "city" && firstCity) {
      firstCity = false;
      held.resolve(route);
      return;
    }
    await route.fulfill({ json: by === "country" ? countryData : cityData });
  });
  await page.goto(fixturePath);
  const geography = page.getByRole("region", { name: "Geography", exact: true });
  const selector = geography.getByRole("combobox", { name: "Geography view" });
  await expect(geography.locator(".bucket-label")).toHaveText(["US", "GB", "Unknown"]);
  await selector.selectOption("city");
  const staleRoute = await held.promise;
  await expect(geography.getByRole("status")).toHaveText("Loading city geography…");
  await expect(geography.getByRole("listitem")).toHaveCount(0);
  await expect(selector).toBeEnabled();
  const cancelled = page.waitForEvent("requestfailed", request => request.url() === staleRoute.request().url());
  await selector.selectOption("country");
  await expect(geography.locator(".bucket-label")).toHaveText(["US", "GB", "Unknown"]);
  await cancelled;
  await selector.selectOption("city");
  await expect(geography.locator(".bucket-label").first()).toHaveText("New York, US");
  await staleRoute.fulfill({ json: { totalClicks: 999, buckets: [{ label: "STALE CITY FIXTURE", clicks: 999 }] } });
  await expect(geography.locator(".bucket-label")).toHaveText(["New York, US", longCity, "Unknown"]);
  await expect(geography.getByText("STALE CITY FIXTURE")).toHaveCount(0);
  await expect(geography.getByRole("alert")).toHaveCount(0);
});

test("fixture: a delayed city response is cancelled on sign-out and cannot restore private geography", async ({ page }) => {
  const held = heldRoute();
  await installAnalyticsFixture(page, async (route, by) => {
    if (by === "city") {
      held.resolve(route);
      return;
    }
    await route.fulfill({ json: countryData });
  });
  await page.goto(fixturePath);
  const geography = page.getByRole("region", { name: "Geography", exact: true });
  await expect(geography.locator(".bucket-label").first()).toHaveText("US");
  await geography.getByRole("combobox", { name: "Geography view" }).selectOption("city");
  const staleRoute = await held.promise;
  await expect(geography.getByRole("status")).toHaveText("Loading city geography…");
  const cancelled = page.waitForEvent("requestfailed", request => request.url() === staleRoute.request().url());
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
  await cancelled;
  await staleRoute.fulfill({ json: cityData });
  expect(await page.evaluate(() => sessionStorage.getItem("shortify.session"))).toBeNull();
  await expect(page.getByRole("region", { name: "Geography", exact: true })).toHaveCount(0);
  await expect(page.getByText("New York, US", { exact: true })).toHaveCount(0);
});

test("fixture: geography authorization rejection invalidates the entire session", async ({ page }) => {
  await installAnalyticsFixture(page, async (route, by) => {
    await route.fulfill(by === "country" ? { json: countryData } : {
      status: 401,
      json: { message: "Fixture session expired", code: "UNAUTHORIZED" },
    });
  });
  await page.goto(fixturePath);
  const geography = page.getByRole("region", { name: "Geography", exact: true });
  await expect(geography.locator(".bucket-label").first()).toHaveText("US");
  await geography.getByRole("combobox", { name: "Geography view" }).selectOption("city");
  await expect(page).toHaveURL(/\/login/);
  expect(await page.evaluate(() => sessionStorage.getItem("shortify.session"))).toBeNull();
  await expect(page.getByRole("region", { name: "Geography", exact: true })).toHaveCount(0);
  await expect(page.locator(".primary-stat")).toHaveCount(0);
});
