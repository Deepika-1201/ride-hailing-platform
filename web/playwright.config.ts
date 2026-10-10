import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: process.env.WEB_BASE_URL ?? "http://127.0.0.1:5173",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    channel: process.env.PLAYWRIGHT_CHANNEL,
    viewport: { width: 1280, height: 800 },
    launchOptions: { args: ["--use-mock-keychain"] },
    screenshot: "only-on-failure",
  },
  webServer: process.env.WEB_BASE_URL ? undefined : {
    command: "npm run dev -- --host 127.0.0.1 --port 5173 --strictPort",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: true,
    timeout: 30_000,
  },
});