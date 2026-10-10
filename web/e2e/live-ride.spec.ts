import { expect, test, type Page, type TestInfo } from "@playwright/test";

async function signIn(page: Page, role: "rider" | "driver", number: number) {
  await page.goto(`/#/${role}`);
  await page.getByRole("spinbutton").fill(String(number));
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.locator(".panel")).toContainText("Live: open");
  await expect(page.locator(".map")).toHaveAttribute("aria-busy", "false");
}

async function mapColours(page: Page): Promise<number> {
  const image = await page.locator("canvas").screenshot();
  return page.evaluate(async (bytes) => {
    const bitmap = await createImageBitmap(new Blob([Uint8Array.from(bytes)], { type: "image/png" }));
    const surface = document.createElement("canvas");
    surface.width = 64;
    surface.height = 64;
    const context = surface.getContext("2d")!;
    context.drawImage(bitmap, 0, 0, 64, 64);
    bitmap.close();
    const pixels = context.getImageData(0, 0, 64, 64).data;
    const colours = new Set<string>();
    for (let offset = 0; offset < pixels.length; offset += 4) {
      colours.add(`${pixels[offset] >> 4},${pixels[offset + 1] >> 4},${pixels[offset + 2] >> 4}`);
    }
    return colours.size;
  }, Array.from(image));
}

async function captureMap(page: Page, name: string, testInfo: TestInfo) {
  await expect.poll(() => mapColours(page)).toBeGreaterThan(8);
  const box = await page.locator(".map").boundingBox();
  expect(box!.width).toBeGreaterThan(300);
  expect(box!.height).toBeGreaterThan(240);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true });
}

test("rider, driver and operations follow a live trip on desktop and mobile", async ({ browser, baseURL }, testInfo) => {
  const context = await browser.newContext({ baseURL, viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
  const externalRequests = new Set<string>();
  await context.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if ((url.protocol === "http:" || url.protocol === "https:") && url.origin !== new URL(baseURL!).origin) {
      externalRequests.add(url.origin);
      await route.abort();
    } else {
      await route.continue();
    }
  });
  const operations = await context.newPage();
  const driver = await context.newPage();
  const rider = await context.newPage();
  const errors: string[] = [];
  for (const page of [operations, driver, rider]) page.on("pageerror", (error) => errors.push(error.message));

  try {
    await operations.goto("/#/ops");
    await expect(operations.locator(".panel")).toContainText("Live: open");
    await signIn(driver, "driver", 1994);
    const previousSession = driver.getByRole("button", { name: "Go offline", exact: true });
    if (await previousSession.isVisible()) {
      await previousSession.click();
      await expect(driver.locator(".panel")).toContainText("OFFLINE");
    }
    await driver.getByRole("button", { name: "Go online", exact: true }).click();
    await expect(driver.locator(".panel")).toContainText("AVAILABLE");
    await signIn(rider, "rider", 9002);

    const map = await rider.locator("canvas").boundingBox();
    expect(map).not.toBeNull();
    await rider.mouse.click(map!.x + map!.width / 2, map!.y + map!.height / 2);
    await rider.mouse.click(map!.x + map!.width / 2 + 24, map!.y + map!.height / 2);
    await rider.getByRole("button", { name: "Get a quote", exact: true }).click();
    await rider.getByRole("button", { name: "Book", exact: true }).click();
    await driver.getByRole("button", { name: "Accept", exact: true }).click();
    await expect(rider.locator(".status")).toHaveText("DRIVER_ASSIGNED");
    await driver.getByRole("button", { name: "Arrived", exact: true }).click();
    await expect(rider.locator(".status")).toHaveText("DRIVER_ARRIVED");
    const pin = await rider.locator(".pin").innerText();
    expect(pin).toMatch(/^\d{4}$/);
    await driver.getByRole("textbox", { name: "Rider's PIN" }).fill(pin);
    await driver.getByRole("button", { name: "Start the trip", exact: true }).click();
    await expect(rider.locator(".status")).toHaveText("IN_TRIP");
    await expect(operations.locator(".legend")).toContainText("on trip 1");
    await expect(operations.locator(".rides")).toContainText("IN_TRIP");
    await operations.locator(".rides li").filter({ hasText: "IN_TRIP" }).first().click();
    await expect.poll(() => operations.locator(".timeline li").count()).toBeGreaterThan(2);
    await captureMap(operations, "operations-desktop", testInfo);
    await operations.setViewportSize({ width: 390, height: 844 });
    await captureMap(operations, "operations-mobile", testInfo);

    await expect(driver.getByText("Driving to the drop-off: 0 m.", { exact: true })).toBeVisible({ timeout: 90_000 });
    await driver.getByRole("button", { name: "Complete", exact: true }).click();
    await expect(rider.locator(".status")).toHaveText("COMPLETED");
    await rider.getByRole("button", { name: /^5/ }).click();
    await expect(rider.getByRole("button", { name: /^5/ })).toHaveCount(0);
    await expect(rider.locator(".error")).toHaveCount(0);
    await driver.getByRole("button", { name: "Go offline", exact: true }).click();
    await expect(driver.locator(".panel")).toContainText("OFFLINE");
    expect(errors).toEqual([]);
    expect([...externalRequests]).toEqual([]);
  } finally {
    const cancel = rider.getByRole("button", { name: "Cancel the ride", exact: true });
    if (await cancel.isVisible().catch(() => false)) await cancel.click().catch(() => undefined);
    const complete = driver.getByRole("button", { name: "Complete", exact: true });
    if (await complete.isVisible().catch(() => false)) await complete.click().catch(() => undefined);
    const offline = driver.getByRole("button", { name: "Go offline", exact: true });
    if (await offline.isVisible().catch(() => false)) {
      await offline.click().catch(() => undefined);
      await expect(driver.locator(".panel")).toContainText("OFFLINE").catch(() => undefined);
    }
    await context.close();
  }
});