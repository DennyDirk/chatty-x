import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, retries: 0,
  timeout: 90_000, expect: { timeout: 15_000 },
  use: { baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:8088', trace: 'retain-on-failure' },
  reporter: [['list'], ['html', { open: 'never' }]],
});
