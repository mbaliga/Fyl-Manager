import { defineConfig, devices } from "@playwright/test";

const exe = process.env.PW_CHROMIUM ?? "/opt/pw-browsers/chromium";
const launchOptions = { executablePath: process.env.PW_USE_BUNDLED ? undefined : exe, args: ["--no-sandbox"] };

export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: 0,
  workers: 2,
  reporter: [["list"]],
  use: { baseURL: "http://localhost:4173", trace: "off", launchOptions },
  webServer: {
    command: "npm run preview",
    url: "http://localhost:4173",
    reuseExistingServer: true,
    timeout: 60_000,
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"], launchOptions }, grepInvert: /@mobile/ },
    { name: "mobile", use: { ...devices["Pixel 7"], launchOptions }, grep: /@mobile/ },
  ],
});
