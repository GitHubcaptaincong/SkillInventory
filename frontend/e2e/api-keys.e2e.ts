import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve, basename } from 'node:path'
const metadata = { id: '1', name: 'policy-plugin-prod-' + 'long-name-'.repeat(10), masked: 'sk_synthetic_…1234', status: 'ACTIVE', createdAt: '2026-10-19T01:30:00Z', expiresAt: null, revokedAt: null }
async function mock(page: Page, options: { refreshFails?: boolean; createFails?: boolean } = {}) {
  let writes = 0, reads = 0, revoked = false
  // Fulfill the production build locally: no socket, backend or credentials required.
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    const asset = pathname.startsWith('/assets/')
    const file = asset ? resolve('dist/assets', basename(pathname)) : resolve('dist/index.html')
    const contentType = !asset ? 'text/html' : pathname.endsWith('.css') ? 'text/css' : 'text/javascript'
    await route.fulfill({ body: readFileSync(file), contentType })
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), method = route.request().method()
    const send = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (url.pathname === '/api/auth/me') return send({ username: 'synthetic-admin' })
    if (url.pathname === '/api/auth/csrf') return send({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' })
    if (url.pathname.endsWith('/revoke')) { revoked = true; writes++; return send({ ...metadata, status: 'REVOKED', revokedAt: '2026-10-19T02:30:00Z' }) }
    if (url.pathname === '/api/admin/api-keys' && method === 'POST') {
      writes++
      if (options.createFails) return route.abort('failed')
      return send({ metadata, key: 'synthetic-browser-only-once-value' }, 201)
    }
    if (url.pathname === '/api/admin/api-keys') {
      reads++
      if (options.refreshFails && writes) return send({ code: 'INTERNAL_ERROR' }, 500)
      return send({ items: [{ ...metadata, ...(revoked ? { status: 'REVOKED', revokedAt: '2026-10-19T02:30:00Z' } : {}) }], total: 41, page: Number(url.searchParams.get('page')), pageSize: 20 })
    }
    return send({ code: 'NOT_FOUND' }, 404)
  })
  return () => ({ writes, reads })
}
test('合成数据：1440/1024/390 布局、抽屉、键盘关闭、复制失败降级及焦点返回', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  await mock(page); await page.goto('/api-keys')
  await expect(page.getByRole('heading', { name: /密钥列表/ })).toBeVisible()
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await expect(page.getByRole('button', { name: '创建 API Key', exact: true })).toBeVisible()
    await page.screenshot({ path: `../.test-output/api-keys-${width}.png`, fullPage: true })
  }
  await page.getByRole('button', { name: '创建 API Key', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByLabel('密钥名称', { exact: true }).fill('synthetic-browser')
  await page.getByLabel('有效期', { exact: true }).selectOption('30')
  await page.keyboard.press('Shift+Tab')
  expect(await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'))).toBe(true)
  await page.getByRole('button', { name: '创建密钥', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'API Key 已创建', exact: true })
  await expect(dialog).toBeVisible()
  await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('synthetic-denied')) } }) })
  await dialog.getByRole('button', { name: '复制密钥', exact: true }).click()
  await expect(dialog).toContainText('自动复制不可用')
  expect(await dialog.locator('textarea').evaluate((el: HTMLTextAreaElement) => el.selectionEnd === el.value.length)).toBe(true)
  await page.keyboard.press('Escape'); await expect(dialog).toContainText('确认已妥善保存')
  await dialog.getByRole('button', { name: '继续保存' }).click()
  await page.mouse.click(1, 1); await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '完成并关闭' }).click()
  await dialog.getByRole('button', { name: '确认关闭并清除' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.locator('textarea').count()).toBe(0)
  expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('创建 API Key')
  expect(errors).toEqual([])
})
test('合成数据：创建成功刷新失败保留明文；会话失效后清除且重连不恢复', async ({ page }) => {
  const calls = await mock(page, { refreshFails: true }); await page.goto('/api-keys')
  await page.getByRole('button', { name: '创建 API Key', exact: true }).click()
  await page.getByLabel('密钥名称', { exact: true }).fill('synthetic-client')
  await page.getByRole('button', { name: '创建密钥', exact: true }).click()
  await expect(page.getByLabel('完整密钥', { exact: true })).toHaveValue('synthetic-browser-only-once-value')
  await expect(page.getByText(/列表刷新失败/)).toBeAttached()
  await page.evaluate(() => window.dispatchEvent(new Event('session-expired')))
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '创建 API Key', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '重新连接并保留输入' }).click()
  await expect(page.getByRole('button', { name: '创建 API Key', exact: true })).toBeEnabled()
  await expect(page.locator('textarea')).toHaveCount(0)
  expect(calls().writes).toBe(1)
})
test('合成数据：创建断连不重试，撤销确认后保留分页', async ({ page }) => {
  const calls = await mock(page, { createFails: true }); await page.goto('/api-keys')
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.locator('.key-pages')).toContainText('第 2 页')
  await page.getByRole('button', { name: '创建 API Key', exact: true }).click()
  await page.getByLabel('密钥名称', { exact: true }).fill('retained-input')
  await page.getByRole('button', { name: '创建密钥', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('创建结果未确认，请刷新列表检查')
  await expect(page.getByLabel('密钥名称', { exact: true })).toHaveValue('retained-input')
  expect(calls().writes).toBe(1)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: /^撤销 / }).click()
  await expect(page.getByRole('dialog')).toContainText('此操作不可恢复')
  await page.getByRole('button', { name: '确认撤销', exact: true }).click()
  await expect(page.getByText('撤销已成功。', { exact: true })).toBeVisible()
  await expect(page.locator('.key-table')).toContainText('已撤销')
  await expect(page.locator('.key-pages')).toContainText('第 2 页')
  expect(calls().writes).toBe(2)
})
