// @vitest-environment jsdom
import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Editor from './Editor.vue'
const resource = { id: '7', name: 'java-review', description: '原始描述', content: '# 正文', status: 'UNPUBLISHED',
  lockVersion: 0, publishedSnapshot: null, createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z', publishedAt: null }
async function setup(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { template: '<p>列表</p>' } }, { path: '/skills/new', component: Editor }, { path: '/skills/:id', component: Editor },
  ] })
  await router.push(path); await router.isReady()
  const wrapper = mount(Editor, { global: { plugins: [router] } }); await flushPromises()
  return { wrapper, router }
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('编辑流程', () => {
  it('409 冲突后保留输入并提示刷新，阻止继续覆盖', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(resource)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: '版本过期', requestId: 'test-id' }), { status: 409 }))
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await setup('/skills/7')
    await wrapper.findAll('textarea')[0]!.setValue('我尚未提交的新描述')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.findAll('textarea')[0]!.element.value).toBe('我尚未提交的新描述')
    expect(wrapper.text()).toContain('已保留你的输入')
    expect(wrapper.find('button.primary').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('有未保存修改时发布仅提示保存，不调用发布接口', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(resource)))
    vi.stubGlobal('fetch', fetch)
    const { wrapper } = await setup('/skills/7')
    await wrapper.findAll('textarea')[1]!.setValue('改动的正文')
    await wrapper.findAll('button').find(b => b.text() === '发布已保存草稿')!.trigger('click')
    expect(wrapper.text()).toContain('请先保存草稿')
    expect(fetch).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('新建保存后进入持久化详情地址', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(resource), { status: 201 })))
    const { wrapper, router } = await setup('/skills/new')
    await wrapper.find('input').setValue('java-review')
    await wrapper.findAll('textarea')[0]!.setValue('描述')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/skills/7')
    wrapper.unmount()
  })
})
