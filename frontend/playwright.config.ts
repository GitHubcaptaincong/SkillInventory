import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './e2e', testMatch: '**/*.e2e.ts', workers: 1, timeout: 45000,
  reporter: 'list', outputDir: '../.test-output/playwright',
  use: { baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:18088', headless: true,
    channel: process.env.BROWSER_CHANNEL || undefined, viewport: { width: 1440, height: 1050 } },
})
