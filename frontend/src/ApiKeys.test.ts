// @vitest-environment jsdom
import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ApiKeys from './ApiKeys.vue'
const key = { id: '1', name: 'synthetic-client', masked: 'sk_demo_…1234', createdAt: '2026-10-19T00:00:00Z', expiresAt: null, revokedAt: null, status: 'ACTIVE' }
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
const listing = (name = key.name, total = 41) => json({ items: [{ ...key, name }], page: 1, pageSize: 20, total })
const wrappers: VueWrapper[] = []
async function setup(handler?: (path: string, init?: RequestInit) => Promise<Response>) {
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith('/csrf')) return json({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })
    if (handler) return handler(path, init)
    if (init?.method === 'POST') return json({ metadata: key, key: 'synthetic-only-once' }, 201)
    return listing()
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api-keys', component: ApiKeys }, { path: '/', component: { template: '<p>home</p>' } }] })
  await router.push('/api-keys'); await router.isReady()
  const w = mount({ template: '<RouterView />' }, { attachTo: document.body, global: { plugins: [router] } }); wrappers.push(w)
  await flushPromises()
  return { w, fetcher, router }
}
const button = (w: VueWrapper, text: string) => w.findAll('button').find(b => b.text() === text)!
async function open(w: VueWrapper) { await button(w, '创建 API Key').trigger('click'); await w.get('#key-name').setValue('saved-draft') }
async function create(w: VueWrapper) { await open(w); await w.get('form').trigger('submit'); await flushPromises() }
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('API Key 安全交互', () => {
  it('首次加载 skeleton，提交中阻止重复写入并禁用关闭', async () => {
    let finishRead!: (r: Response) => void, finishWrite!: (r: Response) => void
    const { w, fetcher } = await setup(async (_, init) => init?.method === 'POST' ? new Promise(r => finishWrite = r) : new Promise(r => finishRead = r))
    expect(w.find('[aria-label="正在加载密钥列表"]').exists()).toBe(true)
    finishRead(listing()); await flushPromises()
    await open(w); await w.get('form').trigger('submit'); await w.get('form').trigger('submit'); await flushPromises()
    expect(w.get('fieldset').attributes('disabled')).toBeDefined()
    expect(w.get('[aria-label="关闭"]').attributes('disabled')).toBeDefined()
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    finishWrite(json({ metadata: key, key: 'synthetic-only-once' }, 201)); await flushPromises()
    expect(w.find('textarea').exists()).toBe(true)
  })
  it('成功后刷新失败仍保留唯一明文，Esc 和关闭都要求确认，清除 DOM 值', async () => {
    let reads = 0
    const { w } = await setup(async (_, init) => init?.method === 'POST' ? json({ metadata: key, key: 'synthetic-only-once' }, 201) : ++reads === 1 ? listing() : json({}, 500))
    await create(w)
    expect(w.text()).toContain('密钥已创建。'); expect(w.text()).toContain('列表刷新失败')
    const input = w.get('textarea').element as HTMLTextAreaElement
    expect(input.value).toBe('synthetic-only-once')
    await w.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(w.text()).toContain('关闭后完整密钥无法找回')
    await button(w, '确认关闭并清除').trigger('click')
    expect(w.find('textarea').exists()).toBe(false); expect(input.value).toBe('')
  })
  it('复制失败选中明文供手动保存，不影响创建结果', async () => {
    vi.stubGlobal('isSecureContext', true)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('blocked')) } })
    const { w } = await setup(); await create(w); await button(w, '复制密钥').trigger('click'); await flushPromises()
    expect(w.text()).toContain('自动复制不可用')
    const el = w.get('textarea').element as HTMLTextAreaElement
    expect(el.selectionEnd).toBe(el.value.length)
  })
  it('创建断连不自动重试，保留名称并明确结果未知', async () => {
    const { w, fetcher } = await setup(async (_, init) => { if (init?.method === 'POST') throw new TypeError('network'); return listing() })
    await create(w)
    expect(w.text()).toContain('创建结果未确认，请刷新列表检查')
    expect((w.get('#key-name').element as HTMLInputElement).value).toBe('saved-draft')
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
  it('会话失效清明文，阻止写入且重连不恢复明文', async () => {
    const { w, fetcher } = await setup(); await create(w)
    window.dispatchEvent(new Event('session-expired')); await flushPromises()
    expect(w.find('textarea').exists()).toBe(false); expect(button(w, '创建 API Key').attributes('disabled')).toBeDefined()
    window.dispatchEvent(new Event('session-restored')); await flushPromises()
    expect(w.find('textarea').exists()).toBe(false)
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
  it('真实 401 保留未提交名称、有效期、页码，403 不误报失效', async () => {
    let status = 401
    const { w } = await setup(async (_, init) => init?.method === 'POST' ? json({ code: status === 401 ? 'UNAUTHORIZED' : 'FORBIDDEN' }, status) : listing())
    await button(w, '下一页').trigger('click'); await flushPromises(); await open(w)
    await w.get('select').setValue('30'); await w.get('form').trigger('submit'); await flushPromises()
    expect(w.text()).toContain('完整密钥已清除'); expect((w.get('#key-name').element as HTMLInputElement).value).toBe('saved-draft')
    expect((w.get('select').element as HTMLSelectElement).value).toBe('30'); expect(w.text()).toContain('第 2 页')
    status = 403; window.dispatchEvent(new Event('session-restored')); await flushPromises()
    await w.get('form').trigger('submit'); await flushPromises()
    expect(w.text()).toContain('CSRF 校验失败'); expect(w.text()).not.toContain('完整密钥已清除')
  })
  it('会话失效后晚返回的创建响应不能重新展示明文', async () => {
    let resolve!: (r: Response) => void
    const { w } = await setup(async (_, init) => init?.method === 'POST' ? new Promise(r => resolve = r) : listing())
    await open(w); await w.get('form').trigger('submit'); await flushPromises()
    window.dispatchEvent(new Event('session-expired'))
    resolve(json({ metadata: key, key: 'synthetic-late-secret' }, 201)); await flushPromises()
    expect(w.find('textarea').exists()).toBe(false)
    expect((w.get('#key-name').element as HTMLInputElement).value).toBe('saved-draft')
  })
  it('撤销确认后更新行，刷新失败仍报告成功并保留页码', async () => {
    let revoked = false
    const { w, fetcher } = await setup(async (path) => {
      if (path.endsWith('/revoke')) { revoked = true; return json({ ...key, status: 'REVOKED', revokedAt: '2026-10-19T01:00:00Z' }) }
      return revoked ? json({}, 500) : listing()
    })
    await button(w, '下一页').trigger('click'); await flushPromises()
    await button(w, '撤销').trigger('click')
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/revoke'))).toHaveLength(0)
    await button(w, '确认撤销').trigger('click'); await flushPromises()
    expect(w.text()).toContain('撤销已成功'); expect(w.text()).toContain('列表刷新失败'); expect(w.text()).toContain('第 2 页')
    expect(w.text()).toContain('已撤销'); expect(w.text()).toContain('撤销于')
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/revoke'))).toHaveLength(1)
  })
  it('分页晚返回的旧请求不能覆盖新页', async () => {
    let resolve!: (r: Response) => void
    const { w } = await setup(async path => path.includes('page=2') ? new Promise(r => resolve = r) : listing(path.includes('page=3') ? 'newest-page' : key.name))
    await button(w, '下一页').trigger('click'); await flushPromises()
    await button(w, '下一页').trigger('click'); await flushPromises()
    resolve(listing('stale-page')); await flushPromises()
    expect(w.text()).toContain('newest-page'); expect(w.text()).not.toContain('stale-page'); expect(w.text()).toContain('第 3 页')
  })
  it('路由离开取消保留明文，确认离开清除', async () => {
    const { w, router } = await setup(); await create(w)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await router.push('/'); expect(w.find('textarea').exists()).toBe(true)
    confirm.mockReturnValue(true); const el = w.get('textarea').element as HTMLTextAreaElement
    await router.push('/'); expect(el.value).toBe(''); expect(w.find('textarea').exists()).toBe(false)
  })
  it('空列表与错误区分，失败可以重试，刷新不清空原表格', async () => {
    let mode = 'error'
    const { w } = await setup(async () => mode === 'error' ? json({}, 500) : mode === 'empty' ? json({ items: [], total: 0, page: 1, pageSize: 20 }) : listing())
    expect(w.text()).toContain('列表加载失败'); expect(w.text()).not.toContain('还没有 API 密钥')
    mode = 'empty'; await button(w, '重试').trigger('click'); await flushPromises(); expect(w.text()).toContain('还没有 API 密钥')
    mode = 'normal'; await button(w, '刷新').trigger('click'); await flushPromises()
    mode = 'error'; await button(w, '刷新').trigger('click'); expect(w.text()).toContain(key.name); await flushPromises(); expect(w.text()).toContain(key.name)
  })
  it('必填、未来日期验证与默认永不过期', async () => {
    const { w, fetcher } = await setup(); await open(w)
    expect((w.get('select').element as HTMLSelectElement).value).toBe('never')
    await w.get('#key-name').setValue(' '); await w.get('form').trigger('submit'); expect(w.text()).toContain('请输入密钥名称')
    await w.get('#key-name').setValue('test'); await w.get('select').setValue('custom'); await w.get('#key-expires').setValue('2000-01-01T00:00'); await w.get('form').trigger('submit')
    expect(w.text()).toContain('请选择未来'); expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  })
})
