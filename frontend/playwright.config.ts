import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  expect: { timeout: 12000 },
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:5173",
    browserName: "chromium",
    channel: "chrome",
    headless: true,
    viewport: { width: 1440, height: 1100 },
  },
  reporter: [
    ["list"],
    [
      "html",
      { outputFolder: "../test-results/playwright-report", open: "never" },
    ],
  ],
  outputDir: "../test-results/browser",
});
